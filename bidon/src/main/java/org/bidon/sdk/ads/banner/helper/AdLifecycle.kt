package org.bidon.sdk.ads.banner.helper

/**
 * Created by Bidon Team on 11/07/2023.
 */
internal enum class AdLifecycle {
    Created,
    Loading,
    Loaded,
    LoadingFailed,
    Displaying,
    Displayed,
    DisplayingFailed,
    Destroyed,
}