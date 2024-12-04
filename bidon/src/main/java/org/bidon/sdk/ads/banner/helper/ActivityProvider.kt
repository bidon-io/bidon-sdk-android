package org.bidon.sdk.ads.banner.helper

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import java.lang.ref.WeakReference

/**
 * Created by Bidon Team on 31/10/2024.
 *
 * Provides the current resumed activity.
 */
internal interface ActivityProvider {
    /**
     * Flow of the current resumed activity.
     */
    val resumedActivityFlow: Flow<WeakReference<Activity?>>

    /**
     * Emits the current resumed activity.
     */
    fun emitActivity(activity: Activity?)
}
