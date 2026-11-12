package com.kooo.evcc.evccapi;

import com.kooo.evcc.evccapi.EvccApiInfo;
import com.kooo.evcc.evccapi.IEvccApiCallback;
import com.kooo.evcc.evccapi.IEvccApiSession;

/** Root entry point for the EVCC public API contract. */
interface IEvccApi {
    EvccApiInfo getApiInfo();
    IEvccApiSession openSession(String packageName, IEvccApiCallback callback);
}
