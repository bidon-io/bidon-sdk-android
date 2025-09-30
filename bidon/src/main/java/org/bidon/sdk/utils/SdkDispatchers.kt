package org.bidon.sdk.utils

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.newSingleThreadContext

/**
 * Created by Bidon Team on 06/02/2023.
 */

@VisibleForTesting
internal var defaultDispatcherOverridden: CoroutineDispatcher? = null

@VisibleForTesting
internal var ioDispatcherOverridden: CoroutineDispatcher? = null

@VisibleForTesting
internal var singleDispatcherOverridden: CoroutineDispatcher? = null

@VisibleForTesting
internal var mainDispatcherOverridden: CoroutineDispatcher? = null

public object SdkDispatchers {
    @OptIn(DelicateCoroutinesApi::class)
    internal val Bidon: CoroutineDispatcher
        get() = singleDispatcherOverridden ?: newSingleThreadContext("Bidon")

    public val Main: CoroutineDispatcher
        get() = mainDispatcherOverridden ?: Dispatchers.Main

    internal val Default: CoroutineDispatcher
        get() = defaultDispatcherOverridden ?: Dispatchers.Default

    internal val IO: CoroutineDispatcher
        get() = ioDispatcherOverridden ?: Dispatchers.IO
}
