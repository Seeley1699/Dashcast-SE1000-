package com.kooo.evcc.evccapi;

import com.kooo.evcc.evccapi.EvccOperationResult;
import com.kooo.evcc.evccapi.EvccPropertyRecord;
import com.kooo.evcc.evccapi.EvccShellResult;
import com.kooo.evcc.evccapi.EvccSourceState;
import com.kooo.evcc.evccapi.EvccVariableRecord;

import com.kooo.evcc.evccapi.EvccMusicState;

/** One-way callback stream. Events carry monotonically increasing session sequence values. */
oneway interface IEvccApiCallback {
    void onSessionClosed(in EvccOperationResult reason);
    void onPropertyEvent(in EvccPropertyRecord record, long sequence, String eventType);
    void onVariableEvent(in EvccVariableRecord record, long sequence, String eventType);
    void onSourceStateChanged(in EvccSourceState state, long sequence);
    void onShellCompleted(in EvccShellResult result);
    void onResyncRequired(long expectedSequence, long actualSequence);
    // Sent only after the client explicitly enables the API 1.1 subscription.
    void onMusicStateChanged(in EvccMusicState state, long sequence, String eventType);
}
