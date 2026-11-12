package com.kooo.evcc.evccapi.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.kooo.evcc.evccapi.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle-aware client for EVCC's public Binder API.
 *
 * Calls on [IEvccApiSession] are synchronous Binder calls; applications should execute potentially
 * slow reads and all writes away from the main thread. Connection and callbacks are dispatched on
 * the main thread. Desired subscriptions are replayed after a Binder reconnect. Explicit
 * authorization rejection or service disablement stops automatic retries; call [connect] again
 * after the user has changed authorization in EVCC.
 */
class EvccApiClient @JvmOverloads constructor(
    context: Context,
    private val clientPackage: String = context.packageName,
    listener: Listener? = null,
) : Closeable {
    enum class ConnectionState { DISCONNECTED, BINDING, CONNECTED, WAITING_TO_RETRY }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState, detail: String) {}
        fun onSessionReady(apiInfo: EvccApiInfo, sessionInfo: EvccSessionInfo) {}
        fun onSessionClosed(reason: EvccOperationResult) {}
        fun onMusicStateChanged(state: EvccMusicState, sequence: Long, eventType: String) {}
        fun onMusicLyrics(lyrics: EvccMusicResources.Lyrics) {}
        fun onPropertyEvent(record: EvccPropertyRecord, sequence: Long, eventType: String) {}
        fun onVariableEvent(record: EvccVariableRecord, sequence: Long, eventType: String) {}
        fun onSourceStateChanged(state: EvccSourceState, sequence: Long) {}
        fun onShellCompleted(result: EvccShellResult) {}
        fun onResyncRequired(expectedSequence: Long, actualSequence: Long) {}
        fun onError(operation: String, error: Throwable) {}
    }

    data class MusicEvent(val state: EvccMusicState, val sequence: Long, val eventType: String)
    data class PropertyEvent(val record: EvccPropertyRecord, val sequence: Long, val eventType: String)
    data class VariableEvent(val record: EvccVariableRecord, val sequence: Long, val eventType: String)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioJob = SupervisorJob()
    private val ioScope = CoroutineScope(ioJob + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()
    private val retryDelaysMs = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    @Volatile private var state = ConnectionState.DISCONNECTED
    @Volatile private var session: IEvccApiSession? = null
    @Volatile private var apiInfo: EvccApiInfo? = null
    @Volatile private var sessionInfo: EvccSessionInfo? = null
    @Volatile private var desiredConnected = false
    @Volatile private var bound = false
    @Volatile private var closed = false
    @Volatile private var connectionEpoch = 0L
    @Volatile private var activeBinder: IBinder? = null
    private var activeDeathRecipient: IBinder.DeathRecipient? = null
    private var activeConnection: ServiceConnection? = null
    private var activeBindTimeout: Runnable? = null
    private var retryAttempt = 0
    @Volatile private var desiredSubscriptions: List<EvccPropertySelector> = emptyList()
    @Volatile private var desiredVariableSubscriptions: List<String> = emptyList()
    private val sequenceTracker = EventSequenceTracker()
    private val resyncInFlight = AtomicBoolean(false)
    private val subscriptionLock = Any()
    @Volatile private var desiredMusicSubscription = false
    @Volatile private var automaticMusicLyrics = true
    @Volatile private var latestMusicState = EvccMusicState()
    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var loadedLyricsKey = ""
    private var failedLyricsLoad = false
    private var lyricsLoadGeneration = 0L


    init { listener?.let(listeners::add) }

    private fun callbackFor(epoch: Long) = object : IEvccApiCallback.Stub() {
        override fun onMusicStateChanged(music: EvccMusicState, sequence: Long, eventType: String) = dispatch {
            if (!isCurrentEpoch(epoch) || !checkSequence(sequence, epoch)) return@dispatch
            if (!desiredMusicSubscription) return@dispatch
            latestMusicState = music
            notifyListeners("onMusicStateChanged") { it.onMusicStateChanged(music, sequence, eventType) }
            if (eventType == EvccApiContract.EventType.INITIAL_SNAPSHOT && lyricsJob?.isActive != true &&
                failedLyricsLoad) loadedLyricsKey = ""
            loadMusicLyrics(music, epoch)
        }

        override fun onSessionClosed(reason: EvccOperationResult) = dispatch {
            if (!isCurrentEpoch(epoch)) return@dispatch
            notifyListeners("onSessionClosed") { it.onSessionClosed(reason) }
            if (!ConnectionRetryPolicy.shouldRetryAfterSessionClosed(reason.status)) {
                stopWithoutRetry("session closed: ${reason.status}")
            } else if (desiredConnected) {
                scheduleReconnect("session closed: ${reason.status}")
            }
        }

        override fun onPropertyEvent(record: EvccPropertyRecord, sequence: Long, eventType: String) = dispatch {
            if (!isCurrentEpoch(epoch) || !checkSequence(sequence, epoch)) return@dispatch
            notifyListeners("onPropertyEvent") { it.onPropertyEvent(record, sequence, eventType) }
        }

        override fun onVariableEvent(record: EvccVariableRecord, sequence: Long, eventType: String) = dispatch {
            if (!isCurrentEpoch(epoch) || !checkSequence(sequence, epoch)) return@dispatch
            notifyListeners("onVariableEvent") { it.onVariableEvent(record, sequence, eventType) }
        }

        override fun onSourceStateChanged(sourceState: EvccSourceState, sequence: Long) = dispatch {
            if (!isCurrentEpoch(epoch) || !checkSequence(sequence, epoch)) return@dispatch
            notifyListeners("onSourceStateChanged") { it.onSourceStateChanged(sourceState, sequence) }
        }

        override fun onShellCompleted(result: EvccShellResult) = dispatch {
            if (!isCurrentEpoch(epoch)) return@dispatch
            notifyListeners("onShellCompleted") { it.onShellCompleted(result) }
        }

        override fun onResyncRequired(expectedSequence: Long, actualSequence: Long) = dispatch {
            if (!isCurrentEpoch(epoch)) return@dispatch
            sequenceTracker.reset(actualSequence)
            notifyListeners("onResyncRequired") { it.onResyncRequired(expectedSequence, actualSequence) }
            session?.let { replayDesiredSubscriptions(it, "server request", epoch) }
        }
    }

    private fun createServiceConnection(epoch: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!isCurrentConnection(epoch, this) || !desiredConnected || closed) return
            synchronized(lock) { activeBinder = binder }
            val deathRecipient = IBinder.DeathRecipient {
                dispatch {
                    if (isCurrentBinding(epoch, binder) && desiredConnected) {
                        scheduleReconnect("binder died")
                    }
                }
            }
            synchronized(lock) { activeDeathRecipient = deathRecipient }
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (error: Throwable) {
                if (isCurrentBinding(epoch, binder)) {
                    notifyListeners("onError") { it.onError("openSession", error) }
                    if (desiredConnected) scheduleReconnect(error.message ?: "openSession failed")
                }
                return
            }
            // Binder calls are intentionally kept off the main thread. A reconnect may need to
            // restore hundreds of subscriptions and must never freeze the client Activity.
            ioScope.launch {
                var openedSession: IEvccApiSession? = null
                try {
                    val root = IEvccApi.Stub.asInterface(binder)
                    val info = root.apiInfo
                    if (info.apiMajor != EvccApiContract.API_MAJOR) {
                        throw IncompatibleApiException("Unsupported EvccApi ${info.apiMajor}.${info.apiMinor}")
                    }
                    val opened = root.openSession(clientPackage, callbackFor(epoch))
                        ?: throw SecurityException("EVCC rejected the session")
                    openedSession = opened
                    val openedInfo = opened.sessionInfo
                    if (!isCurrentBinding(epoch, binder) || !binder.isBinderAlive) {
                        runCatching { opened.close() }
                        return@launch
                    }
                    dispatch {
                        if (!isCurrentBinding(epoch, binder)) {
                            ioScope.launch { runCatching { opened.close() } }
                            return@dispatch
                        }
                        apiInfo = info
                        session = opened
                        sessionInfo = openedInfo
                        retryAttempt = 0
                        cancelBindTimeout()
                        sequenceTracker.reset()
                        resyncInFlight.set(false)
                        updateState(ConnectionState.CONNECTED, "session ${openedInfo.sessionId}")
                        notifyListeners("onSessionReady") { it.onSessionReady(info, openedInfo) }
                        replayDesiredSubscriptions(opened, "reconnect", epoch)
                    }
                } catch (error: Throwable) {
                    openedSession?.let { runCatching { it.close() } }
                    dispatch {
                        if (!isCurrentBinding(epoch, binder)) return@dispatch
                        notifyListeners("onError") { it.onError("openSession", error) }
                        if (error is SecurityException || error is IncompatibleApiException) {
                            stopWithoutRetry(error.message ?: "session rejected")
                        } else if (desiredConnected) {
                            scheduleReconnect(error.message ?: "openSession failed")
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (isCurrentConnection(epoch, this) && desiredConnected) scheduleReconnect("service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            if (isCurrentConnection(epoch, this) && desiredConnected) scheduleReconnect("binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            if (isCurrentConnection(epoch, this) && desiredConnected) {
                stopWithoutRetry("service returned a null binding")
            }
        }
    }

    fun addListener(listener: Listener) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun connect() {
        if (closed) {
            dispatch {
                notifyListeners("onError") {
                    it.onError("connect", IllegalStateException("EvccApiClient is closed"))
                }
            }
            return
        }
        synchronized(lock) { desiredConnected = true }
        dispatch { bindNow() }
    }

    fun disconnect() {
        synchronized(lock) { desiredConnected = false }
        dispatch {
            mainHandler.removeCallbacksAndMessages(RETRY_TOKEN)
            closeSessionAsync(safelyUnbind())
            updateState(ConnectionState.DISCONNECTED, "client disconnected")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        disconnect()
        mainHandler.postDelayed({ ioScope.cancel() }, IO_SHUTDOWN_DELAY_MS)
    }

    fun connectionState(): ConnectionState = state
    fun currentApiInfo(): EvccApiInfo? = apiInfo
    fun currentSessionInfo(): EvccSessionInfo? = sessionInfo
    fun currentSession(): IEvccApiSession? = session

    fun openSettings(): Boolean = try {
        val intent = Intent(EvccApiContract.ACTION_OPEN_SETTINGS)
            .setPackage(EvccApiContract.EVCC_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        true
    } catch (error: Throwable) {
        dispatch { notifyListeners("onError") { it.onError("openSettings", error) } }
        false
    }

    /** Opens this client's own grant editor. It never chooses a preset or grants permissions. */
    fun openAuthorization(): Boolean = try {
        appContext.startActivity(Intent(EvccApiContract.ACTION_OPEN_API_AUTHORIZATION)
            .setPackage(EvccApiContract.EVCC_PACKAGE)
            .putExtra(EvccApiContract.EXTRA_PACKAGE_NAME, appContext.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: Throwable) {
        dispatch { notifyListeners("onError") { it.onError("openAuthorization", error) } }
        false
    }

    @Throws(RemoteException::class)
    fun queryCapabilities(): List<EvccCapability> = requireSession().queryCapabilities().orEmpty()

    @Throws(RemoteException::class)
    fun resolveProperty(selector: EvccPropertySelector): EvccPropertyRecord =
        requireSession().resolveProperty(selector)

    @Throws(RemoteException::class)
    fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean = false): EvccPropertyRecord =
        requireSession().readProperty(selector, forceRefresh)

    @Throws(RemoteException::class)
    fun readProperties(selectors: List<EvccPropertySelector>, forceRefresh: Boolean = false): List<EvccPropertyRecord> {
        require(selectors.size <= EvccApiContract.MAX_BATCH_SIZE) {
            "At most ${EvccApiContract.MAX_BATCH_SIZE} properties may be read per request"
        }
        return requireSession().readProperties(selectors, forceRefresh).orEmpty()
    }

    @Throws(RemoteException::class)
    fun replaceSubscriptions(selectors: List<EvccPropertySelector>): EvccOperationResult {
        val normalized = selectors.distinct()
        require(normalized.size <= EvccApiContract.MAX_PROPERTY_SUBSCRIPTIONS) {
            "At most ${EvccApiContract.MAX_PROPERTY_SUBSCRIPTIONS} property subscriptions are allowed"
        }
        return synchronized(subscriptionLock) {
            requireSession().replaceSubscriptions(normalized).also { result ->
                if (result.isSuccess) desiredSubscriptions = normalized
            }
        }
    }

    @Throws(RemoteException::class)
    fun queryPropertyDirectory(request: EvccPageRequest): EvccPropertyPage {
        require(request.limit in 1..EvccApiContract.MAX_PAGE_SIZE) {
            "Directory page size must be between 1 and ${EvccApiContract.MAX_PAGE_SIZE}"
        }
        return requireSession().queryPropertyDirectory(request)
    }

    @Throws(RemoteException::class)
    fun querySourceStates(): List<EvccSourceState> = requireSession().querySourceStates().orEmpty()

    @Throws(RemoteException::class)
    fun writeProperty(request: EvccWriteRequest): EvccOperationResult = requireSession().writeProperty(request)
    @Throws(RemoteException::class)
    fun writeProperties(requests: List<EvccWriteRequest>): List<EvccOperationResult> {
        require(requests.size <= EvccApiContract.MAX_BATCH_SIZE) {
            "At most ${EvccApiContract.MAX_BATCH_SIZE} properties may be written per request"
        }
        return requireSession().writeProperties(requests).orEmpty()
    }
    @Throws(RemoteException::class)
    fun queryVariables(): List<EvccVariableRecord> = requireSession().queryVariables().orEmpty()
    @Throws(RemoteException::class)
    fun readVariable(name: String): EvccVariableRecord = requireSession().readVariable(name)
    @Throws(RemoteException::class)
    fun replaceVariableSubscriptions(names: List<String>): EvccOperationResult {
        val normalized = names.map(String::trim).filter(String::isNotEmpty).distinct()
        require(normalized.size <= EvccApiContract.MAX_VARIABLE_SUBSCRIPTIONS) {
            "At most ${EvccApiContract.MAX_VARIABLE_SUBSCRIPTIONS} variable subscriptions are allowed"
        }
        return synchronized(subscriptionLock) {
            requireSession().replaceVariableSubscriptions(normalized).also { result ->
                if (result.isSuccess) desiredVariableSubscriptions = normalized
            }
        }
    }
    @Throws(RemoteException::class)
    fun writeVariable(request: EvccVariableWriteRequest): EvccOperationResult = requireSession().writeVariable(request)
    @Throws(RemoteException::class)
    fun deleteVariable(name: String): EvccOperationResult = requireSession().deleteVariable(name)
    @Throws(RemoteException::class)
    fun executeAction(request: EvccActionRequest): EvccOperationResult = requireSession().executeAction(request)
    @Throws(RemoteException::class)
    fun executeShell(request: EvccShellRequest): EvccOperationResult = requireSession().executeShell(request)
    @Throws(RemoteException::class)
    fun cancel(requestId: String): EvccOperationResult = requireSession().cancel(requestId)

    private fun volumeSupported(): Boolean = supportsVolumeApi(apiInfo?.apiMajor ?: 0, apiInfo?.apiMinor ?: 0)

    fun queryVolumeGroups(): EvccVolumeGroupsResult {
        val target = requireSession()
        return if (volumeSupported()) target.queryVolumeGroups() else EvccVolumeGroupsResult(status = EvccApiContract.Status.UNSUPPORTED)
    }
    fun readVolumeGroup(groupId: Int): EvccVolumeGroupState {
        val target = requireSession()
        return if (volumeSupported()) target.readVolumeGroup(groupId) else EvccVolumeGroupState(status = EvccApiContract.Status.UNSUPPORTED, groupId = groupId)
    }
    fun setVolumeGroup(request: EvccVolumeSetRequest): EvccVolumeWriteResult {
        val target = requireSession()
        return if (volumeSupported()) target.setVolumeGroup(request) else EvccVolumeWriteResult(request.requestId, EvccApiContract.Status.UNSUPPORTED, groupId = request.groupId)
    }
    fun adjustVolumeGroup(request: EvccVolumeAdjustRequest): EvccVolumeWriteResult {
        val target = requireSession()
        return if (volumeSupported()) target.adjustVolumeGroup(request) else EvccVolumeWriteResult(request.requestId, EvccApiContract.Status.UNSUPPORTED, groupId = request.groupId)
    }

    private fun musicSupported(): Boolean = (apiInfo?.apiMinor ?: 0) >= 1

    fun readMusicState(): EvccMusicState {
        val target = requireSession()
        return if (musicSupported()) target.readMusicState() else EvccMusicState(status = EvccApiContract.Status.UNSUPPORTED)
    }

    fun setMusicSubscription(enabled: Boolean): EvccOperationResult = synchronized(subscriptionLock) {
        val target = requireSession()
        if (!musicSupported()) return@synchronized EvccOperationResult(status = EvccApiContract.Status.UNSUPPORTED)
        val previous = desiredMusicSubscription
        desiredMusicSubscription = enabled
        try {
            target.setMusicSubscription(enabled).also {
                if (!it.isSuccess) desiredMusicSubscription = previous
                else if (!enabled) dispatch { clearMusicState() }
            }
        } catch (error: Throwable) { desiredMusicSubscription = previous; throw error }
    }

    fun openMusicLyrics(trackId: String, revision: Long): EvccMusicResource = openMusicResource(trackId, revision, true)
    fun openMusicArtwork(trackId: String, revision: Long): EvccMusicResource = openMusicResource(trackId, revision, false)
    private fun openMusicResource(trackId: String, revision: Long, lyrics: Boolean): EvccMusicResource {
        val target = requireSession()
        val epoch = connectionEpoch
        if (!musicSupported()) return EvccMusicResource(status = EvccApiContract.Status.UNSUPPORTED)
        val result = if (lyrics) target.openMusicLyrics(trackId, revision) else target.openMusicArtwork(trackId, revision)
        if (!isCurrentEpoch(epoch) || session !== target) {
            result.close()
            return EvccMusicResource(status = EvccApiContract.Status.SESSION_CLOSED)
        }
        if (result.status == EvccApiContract.Status.AVAILABLE &&
            (result.trackId != trackId || result.revision != revision)) {
            result.close()
            return EvccMusicResource(status = EvccApiContract.Status.STALE_RESOURCE, trackId = trackId, revision = revision)
        }
        return result
    }
    fun readMusicLyrics(state: EvccMusicState): EvccMusicResources.Lyrics =
        EvccMusicResources.readLyrics(openMusicLyrics(state.trackId, state.lyricsRevision))
    fun openMusicArtworkStream(state: EvccMusicState): java.io.InputStream =
        EvccMusicResources.openStream(openMusicArtwork(state.trackId, state.artworkRevision))

    fun sendMusicCommand(command: EvccMusicCommand): EvccOperationResult {
        val target = requireSession()
        return if (musicSupported()) target.sendMusicCommand(command) else EvccOperationResult(status = EvccApiContract.Status.UNSUPPORTED)
    }

    /** Enabled by default; disabling cancels automatic reads, leaving explicit reads available. */
    fun setAutomaticMusicLyrics(enabled: Boolean) = dispatch {
        automaticMusicLyrics = enabled
        lyricsLoadGeneration++
        lyricsJob?.cancel(); lyricsJob = null; loadedLyricsKey = ""
        if (enabled && desiredMusicSubscription) loadMusicLyrics(latestMusicState, connectionEpoch)
    }

    private fun clearMusicState() {
        lyricsLoadGeneration++
        lyricsJob?.cancel(); lyricsJob = null; loadedLyricsKey = ""
        latestMusicState = EvccMusicState()
        notifyListeners("onMusicLyrics") { it.onMusicLyrics(EvccMusicResources.Lyrics(EvccApiContract.Status.UNAVAILABLE, "", 0, "")) }
    }

    private fun loadMusicLyrics(music: EvccMusicState, epoch: Long) {
        if (!automaticMusicLyrics) return
        val key = "${music.trackId}:${music.lyricsRevision}:${music.lyricsStatus}"
        if (key == loadedLyricsKey) return
        val generation = ++lyricsLoadGeneration
        loadedLyricsKey = key
        failedLyricsLoad = false
        lyricsJob?.cancel()
        notifyListeners("onMusicLyrics") { it.onMusicLyrics(EvccMusicResources.Lyrics(if (music.lyricsStatus == EvccApiContract.Status.AVAILABLE) EvccApiContract.Status.PENDING else music.lyricsStatus, music.trackId, music.lyricsRevision, "")) }
        if (music.lyricsStatus != EvccApiContract.Status.AVAILABLE) return
        lyricsJob = ioScope.launch {
            try {
                var result = readMusicLyrics(music)
                // At most two retries for a transient failure; repeated progress events do not redownload.
                repeat(2) { attempt ->
                    if (result.status in setOf(EvccApiContract.Status.FAILED, EvccApiContract.Status.TIMEOUT,
                            EvccApiContract.Status.RATE_LIMITED, EvccApiContract.Status.PENDING)) {
                        kotlinx.coroutines.delay(1_000L * (attempt + 1))
                        result = readMusicLyrics(music)
                    }
                }
                dispatch {
                    if (!isCurrentEpoch(epoch) || key != loadedLyricsKey || generation != lyricsLoadGeneration || !desiredMusicSubscription || !automaticMusicLyrics) return@dispatch
                    failedLyricsLoad = result.status != EvccApiContract.Status.AVAILABLE
                    notifyListeners("onMusicLyrics") { it.onMusicLyrics(result) }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                dispatch {
                    if (isCurrentEpoch(epoch) && key == loadedLyricsKey && generation == lyricsLoadGeneration) {
                        failedLyricsLoad = true
                        notifyListeners("onMusicLyrics") { it.onMusicLyrics(EvccMusicResources.Lyrics(EvccApiContract.Status.FAILED, music.trackId, music.lyricsRevision, "")) }
                        notifyListeners("onError") { it.onError("music.lyrics", error) }
                    }
                }
            }
        }
    }

    fun musicEvents(): Flow<MusicEvent> = callbackFlow {
        val listener = object : Listener {
            override fun onMusicStateChanged(state: EvccMusicState, sequence: Long, eventType: String) {
                trySend(MusicEvent(state, sequence, eventType))
            }
        }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }.buffer(kotlinx.coroutines.channels.Channel.CONFLATED)

    fun propertyEvents(): Flow<PropertyEvent> = callbackFlow {
        val listener = object : Listener {
            override fun onPropertyEvent(record: EvccPropertyRecord, sequence: Long, eventType: String) {
                trySend(PropertyEvent(record, sequence, eventType))
            }
        }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

    fun variableEvents(): Flow<VariableEvent> = callbackFlow {
        val listener = object : Listener {
            override fun onVariableEvent(record: EvccVariableRecord, sequence: Long, eventType: String) {
                trySend(VariableEvent(record, sequence, eventType))
            }
        }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

    private fun requireSession(): IEvccApiSession = session
        ?: throw IllegalStateException("EvccApi session is not connected")

    private fun bindNow() {
        if (closed || !desiredConnected || bound || state == ConnectionState.BINDING || state == ConnectionState.CONNECTED) return
        updateState(ConnectionState.BINDING, "binding")
        val intent = Intent(EvccApiContract.ACTION_BIND).setPackage(EvccApiContract.EVCC_PACKAGE)
        val (epoch, connection) = synchronized(lock) {
            bound = true
            connectionEpoch += 1
            val currentEpoch = connectionEpoch
            val currentConnection = createServiceConnection(currentEpoch)
            activeConnection = currentConnection
            currentEpoch to currentConnection
        }
        val accepted = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: Throwable) {
            notifyListeners("onError") { it.onError("bindService", error) }
            if (error is SecurityException) {
                stopWithoutRetry(error.message ?: "binding rejected")
                return
            }
            false
        }
        if (!accepted) {
            if (isCurrentConnection(epoch, connection)) scheduleReconnect("EVCC service unavailable")
        } else {
            armBindTimeout(epoch, connection)
        }
    }

    private fun scheduleReconnect(detail: String) {
        if (closed || !desiredConnected) return
        closeSessionAsync(safelyUnbind())
        val delay = retryDelaysMs[retryAttempt.coerceAtMost(retryDelaysMs.lastIndex)]
        retryAttempt = (retryAttempt + 1).coerceAtMost(retryDelaysMs.size)
        updateState(ConnectionState.WAITING_TO_RETRY, "$detail; retry in ${delay}ms")
        mainHandler.removeCallbacksAndMessages(RETRY_TOKEN)
        mainHandler.postAtTime({ bindNow() }, RETRY_TOKEN, android.os.SystemClock.uptimeMillis() + delay)
    }

    private fun stopWithoutRetry(detail: String) {
        synchronized(lock) { desiredConnected = false }
        mainHandler.removeCallbacksAndMessages(RETRY_TOKEN)
        closeSessionAsync(safelyUnbind())
        updateState(ConnectionState.DISCONNECTED, "$detail; user action required")
    }

    private fun safelyUnbind(): IEvccApiSession? {
        val oldSession = session
        var timeoutToCancel: Runnable? = null
        val (wasBound, stateToRelease) = synchronized(lock) {
            val result = bound
            bound = false
            connectionEpoch += 1
            val state = Triple(activeBinder, activeDeathRecipient, activeConnection)
            activeBinder = null
            activeDeathRecipient = null
            activeConnection = null
            timeoutToCancel = activeBindTimeout
            activeBindTimeout = null
            result to state
        }
        timeoutToCancel?.let(mainHandler::removeCallbacks)
        val (binder, recipient, connection) = stateToRelease
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        clearRemoteState()
        if (wasBound && connection != null) {
            try { appContext.unbindService(connection) } catch (_: IllegalArgumentException) {}
        }
        return oldSession
    }

    private fun clearRemoteState() {
        clearMusicState()
        session = null
        apiInfo = null
        sessionInfo = null
        sequenceTracker.reset()
        resyncInFlight.set(false)
    }

    private fun checkSequence(sequence: Long, epoch: Long): Boolean {
        val observation = sequenceTracker.observe(sequence)
        if (!observation.accept) return false
        observation.expectedSequence?.let { expected ->
            notifyListeners("onResyncRequired") { it.onResyncRequired(expected, sequence) }
            session?.let { replayDesiredSubscriptions(it, "event gap", epoch) }
        }
        return true
    }

    /** Replacing subscriptions emits fresh INITIAL_SNAPSHOT events and closes an event gap. */
    private fun replayDesiredSubscriptions(target: IEvccApiSession, reason: String, epoch: Long) {
        val gapReplay = reason == "event gap" || reason == "server request"
        if (gapReplay && !resyncInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                if (!isCurrentEpoch(epoch)) return@launch
                val (propertyResult, variableResult) = synchronized(subscriptionLock) {
                    if (!isCurrentEpoch(epoch)) return@synchronized null to null
                    val property = if (desiredSubscriptions.isNotEmpty()) {
                        target.replaceSubscriptions(desiredSubscriptions)
                    } else null
                    val variable = if (desiredVariableSubscriptions.isNotEmpty()) {
                        target.replaceVariableSubscriptions(desiredVariableSubscriptions)
                    } else null
                    if (desiredMusicSubscription && musicSupported()) {
                        val musicResult = target.setMusicSubscription(true)
                        if (!musicResult.isSuccess) return@synchronized musicResult to variable
                    }
                    property to variable
                }
                listOfNotNull(propertyResult, variableResult).firstOrNull { !it.isSuccess }?.let { failed ->
                    dispatch {
                        if (!isCurrentEpoch(epoch)) return@dispatch
                        notifyListeners("onError") {
                            it.onError("subscription resync ($reason)", IllegalStateException(failed.message))
                        }
                    }
                }
            } catch (error: Throwable) {
                dispatch {
                    if (!isCurrentEpoch(epoch)) return@dispatch
                    notifyListeners("onError") { it.onError("subscription resync ($reason)", error) }
                }
            } finally {
                // A stale replay must not clear the guard belonging to a newer connection epoch.
                if (gapReplay && isCurrentEpoch(epoch)) resyncInFlight.set(false)
            }
        }
    }

    private fun isCurrentEpoch(epoch: Long): Boolean =
        synchronized(lock) { desiredConnected && bound && connectionEpoch == epoch }

    private fun isCurrentBinding(epoch: Long, binder: IBinder): Boolean =
        synchronized(lock) {
            desiredConnected && bound && connectionEpoch == epoch && activeBinder === binder
        }

    private fun isCurrentConnection(epoch: Long, connection: ServiceConnection): Boolean =
        synchronized(lock) {
            desiredConnected && bound && connectionEpoch == epoch && activeConnection === connection
        }

    private fun armBindTimeout(epoch: Long, connection: ServiceConnection) {
        val timeout = Runnable {
            if (isCurrentConnection(epoch, connection) && session == null) {
                scheduleReconnect("binding handshake timed out")
            }
        }
        val shouldPost = synchronized(lock) {
            if (!isCurrentConnection(epoch, connection)) false
            else {
                activeBindTimeout = timeout
                true
            }
        }
        if (shouldPost) mainHandler.postDelayed(timeout, BIND_HANDSHAKE_TIMEOUT_MS)
    }

    private fun cancelBindTimeout() {
        val timeout = synchronized(lock) {
            activeBindTimeout.also { activeBindTimeout = null }
        }
        timeout?.let(mainHandler::removeCallbacks)
    }

    private fun closeSessionAsync(target: IEvccApiSession?) {
        if (target == null || ioJob.isCancelled) return
        ioScope.launch { runCatching { target.close() } }
    }

    private fun updateState(newState: ConnectionState, detail: String) {
        state = newState
        notifyListeners("onConnectionStateChanged") { it.onConnectionStateChanged(newState, detail) }
    }

    private fun notifyListeners(callback: String, notification: (Listener) -> Unit) {
        listeners.forEach { listener ->
            try {
                notification(listener)
            } catch (error: Throwable) {
                Log.w(TAG, "Listener failed in $callback", error)
            }
        }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        private class IncompatibleApiException(message: String) : IllegalStateException(message)

        const val TAG = "EvccApiClient"
        val RETRY_TOKEN = Any()
        const val BIND_HANDSHAKE_TIMEOUT_MS = 15_000L
        const val IO_SHUTDOWN_DELAY_MS = 1_000L
    }
}
