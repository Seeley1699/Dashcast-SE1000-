package com.kooo.evcc.evccapi;

import com.kooo.evcc.evccapi.EvccActionRequest;
import com.kooo.evcc.evccapi.EvccCapability;
import com.kooo.evcc.evccapi.EvccOperationResult;
import com.kooo.evcc.evccapi.EvccPageRequest;
import com.kooo.evcc.evccapi.EvccPropertyPage;
import com.kooo.evcc.evccapi.EvccPropertyRecord;
import com.kooo.evcc.evccapi.EvccPropertySelector;
import com.kooo.evcc.evccapi.EvccSessionInfo;
import com.kooo.evcc.evccapi.EvccShellRequest;
import com.kooo.evcc.evccapi.EvccSourceState;
import com.kooo.evcc.evccapi.EvccVariableRecord;
import com.kooo.evcc.evccapi.EvccVariableWriteRequest;
import com.kooo.evcc.evccapi.EvccWriteRequest;

import com.kooo.evcc.evccapi.EvccMusicState;
import com.kooo.evcc.evccapi.EvccMusicResource;
import com.kooo.evcc.evccapi.EvccMusicCommand;

import com.kooo.evcc.evccapi.EvccVolumeGroupState;
import com.kooo.evcc.evccapi.EvccVolumeGroupsResult;
import com.kooo.evcc.evccapi.EvccVolumeSetRequest;
import com.kooo.evcc.evccapi.EvccVolumeAdjustRequest;
import com.kooo.evcc.evccapi.EvccVolumeWriteResult;

/** Per-client authorized session. All mutating operations return per-request status. */
interface IEvccApiSession {
    EvccSessionInfo getSessionInfo();
    List<EvccCapability> queryCapabilities();
    EvccPropertyRecord resolveProperty(in EvccPropertySelector selector);
    EvccPropertyRecord readProperty(in EvccPropertySelector selector, boolean forceRefresh);
    List<EvccPropertyRecord> readProperties(in List<EvccPropertySelector> selectors, boolean forceRefresh);
    EvccOperationResult replaceSubscriptions(in List<EvccPropertySelector> selectors);
    EvccPropertyPage queryPropertyDirectory(in EvccPageRequest request);
    List<EvccSourceState> querySourceStates();
    EvccOperationResult writeProperty(in EvccWriteRequest request);
    List<EvccOperationResult> writeProperties(in List<EvccWriteRequest> requests);
    List<EvccVariableRecord> queryVariables();
    EvccVariableRecord readVariable(String name);
    EvccOperationResult replaceVariableSubscriptions(in List<String> names);
    EvccOperationResult writeVariable(in EvccVariableWriteRequest request);
    EvccOperationResult deleteVariable(String name);
    EvccOperationResult executeAction(in EvccActionRequest request);
    EvccOperationResult executeShell(in EvccShellRequest request);
    EvccOperationResult cancel(String requestId);
    void close();
    // API 1.1: append-only. Require apiMinor >= 1 before calling.
    EvccMusicState readMusicState();
    EvccOperationResult setMusicSubscription(boolean enabled);
    EvccMusicResource openMusicLyrics(String trackId, long revision);
    EvccMusicResource openMusicArtwork(String trackId, long revision);
    EvccOperationResult sendMusicCommand(in EvccMusicCommand request);
    // API 1.2: append-only. Require apiMinor >= 2 before calling.
    EvccVolumeGroupsResult queryVolumeGroups();
    EvccVolumeGroupState readVolumeGroup(int groupId);
    EvccVolumeWriteResult setVolumeGroup(in EvccVolumeSetRequest request);
    EvccVolumeWriteResult adjustVolumeGroup(in EvccVolumeAdjustRequest request);
}
