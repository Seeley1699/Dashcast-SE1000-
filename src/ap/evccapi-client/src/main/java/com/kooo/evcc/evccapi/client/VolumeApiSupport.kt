package com.kooo.evcc.evccapi.client

internal fun supportsVolumeApi(major: Int, minor: Int): Boolean = major == 1 && minor >= 2
