package org.bidon.sdk.auction.ext

import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.auction.models.LineItem

/**
 * Created by Aleksei Cherniaev on 31/07/2023.
 *
 * Finding first [LineItem], which has the minimum LineItem.pricefloor, but greater then given [pricefloor].
 */
fun List<LineItem>.minByPricefloorOrNull(demandId: DemandId, pricefloor: Double): LineItem? {
    return this
        .filter { it.demandId == demandId.demandId }
        .filterNot { it.adUnitId.isNullOrBlank() }
        .sortedBy { it.pricefloor }
        .firstOrNull { it.pricefloor > pricefloor }
}