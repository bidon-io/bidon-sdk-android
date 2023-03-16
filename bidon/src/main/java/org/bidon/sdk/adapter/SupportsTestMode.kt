package org.bidon.sdk.adapter

/**
 * Created by Aleksei Cherniaev on 16/03/2023.
 *
 * Shows if an adapter supports test mode.
 */
interface SupportsTestMode {
    val isTestMode: Boolean
    fun setTestMode(isTestMode: Boolean)
}

class SupportsTestModeImpl: SupportsTestMode {
    override var isTestMode: Boolean = false

    override fun setTestMode(isTestMode: Boolean) {
        this.isTestMode = isTestMode
    }
}