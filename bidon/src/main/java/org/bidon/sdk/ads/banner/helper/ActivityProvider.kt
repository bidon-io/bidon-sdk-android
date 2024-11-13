package org.bidon.sdk.ads.banner.helper

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import java.lang.ref.WeakReference

internal interface ActivityProvider {
    val resumedActivityFlow: Flow<WeakReference<Activity?>>
    fun emitActivity(activity: Activity?)
}