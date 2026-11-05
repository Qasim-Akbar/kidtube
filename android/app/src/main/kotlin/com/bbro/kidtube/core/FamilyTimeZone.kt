package com.bbro.kidtube.core

import java.time.ZoneId

object FamilyTimeZone {
    /** Default family timezone (SPEC section 6). The server sends the real one in the sync snapshot. */
    const val DEFAULT_ID = "Asia/Karachi"

    val DEFAULT: ZoneId = ZoneId.of(DEFAULT_ID)
}
