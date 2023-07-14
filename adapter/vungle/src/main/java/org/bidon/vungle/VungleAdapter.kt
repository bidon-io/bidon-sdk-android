package org.bidon.vungle

import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.vungle.ext.adapterVersion
import org.bidon.vungle.ext.sdkVersion

/**
 * Created by Aleksei Cherniaev on 14/07/2023.
 */
internal val VungleDemandId = DemandId("vungle")

class VungleAdapter : Adapter {
    override val demandId: DemandId = VungleDemandId
    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )
}