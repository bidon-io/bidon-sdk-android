package org.bidon.sdk.adapter

import android.content.Context

/**
 * Created by Aleksei Cherniaev on 30/05/2023.
 */

sealed interface AdSourceType<T : AdAuctionParams> {
    /**
     * Classic mediation ad network
     */
    interface Network<T : AdAuctionParams> : AdSourceType<T> {
        fun fill(adParams: T)
    }

    /**
     * Bidding ad network
     */
    interface Bidding<T : AdAuctionParams> : AdSourceType<T> {
        fun getToken(context: Context): String?
        fun bid(adParams: T, payload: String)
        fun fill()
    }
}