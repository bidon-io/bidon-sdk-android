package org.bidon.mobilefuse.ext

import com.mobilefuse.sdk.MobileFuse
import org.bidon.mobilefuse.BuildConfig

/**
 * Created by Bidon Team on 27/09/2023.
 */
internal const val adapterVersion = BuildConfig.ADAPTER_VERSION
internal val sdkVersion = MobileFuse.getSdkVersion()