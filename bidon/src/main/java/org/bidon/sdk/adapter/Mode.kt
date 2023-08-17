package org.bidon.sdk.adapter

import android.content.Context

/**
 * Created by Aleksei Cherniaev on 30/05/2023.
 *
 * [AdSource] working modes: [Mode.Network], [Mode.Bidding]
 */
sealed interface Mode {
    interface Network : Mode
    interface Bidding : Mode {
        suspend fun getToken(context: Context): String?
    }
}