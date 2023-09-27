package org.bidon.amazon

import org.bidon.sdk.adapter.AdapterParameters
import org.bidon.sdk.ads.AdType

data class AmazonParameters(
    val appKey: String,
    val slots: Map<SlotType, List<String>>
) : AdapterParameters
