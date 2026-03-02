package org.bidon.sdk.ads.cache.andr.execution

internal data class AuctionContext(
    val id: String,
    val configurationId: Long,
    val configurationUid: String,
    val externalWinNotificationsEnabled: Boolean,
)