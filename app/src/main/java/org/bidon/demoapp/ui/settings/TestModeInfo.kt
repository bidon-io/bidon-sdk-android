package org.bidon.demoapp.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Created by Bidon Team on 22/06/2023.
 */
internal object TestModeInfo {
    val isTesMode = MutableStateFlow(false)
}