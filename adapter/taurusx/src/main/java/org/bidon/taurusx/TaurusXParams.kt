package org.bidon.taurusx

import org.bidon.sdk.adapter.AdapterParameters

internal class TaurusXParams(
    val appId: String,
    val channel: String,
    val placementIds: List<TaurusXPlacement>,
) : AdapterParameters

internal data class TaurusXPlacement(
    val adUnitId: String,
    val adFormat: String
)