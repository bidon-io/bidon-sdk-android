package org.bidon.sdk.ads.cache.impl.andr

internal data class AuctionContext(
    val id: String,
    val configurationId: Long,
    val configurationUid: String,
    val externalWinNotificationsEnabled: Boolean,
)