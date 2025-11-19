package org.bidon.ironsource.ext

import com.unity3d.ironsourceads.IronSourceAds
import org.bidon.ironsource.BuildConfig

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = IronSourceAds.getSdkVersion()
