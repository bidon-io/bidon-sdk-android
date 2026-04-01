package org.bidon.sdk.ads.cache.twolevel.storage

internal sealed class InsertResult {
    data object Success : InsertResult()
    data class Rejected(val reason: Reason) : InsertResult()

    enum class Reason {
        Threshold,
        StickyProtected,
        CacheFull,
    }

    val isInserted: Boolean get() = this is Success
}
