package org.bidon.sdk.ads.banner.helper.impl

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.sdk.ads.banner.helper.ActivityProvider
import java.lang.ref.WeakReference

internal class ActivityProviderImpl(application: Application) : ActivityProvider {

    override val resumedActivityFlow = MutableSharedFlow<WeakReference<Activity?>>(replay = 1)
    private var currentActivityRef: WeakReference<Activity?> = WeakReference(null)

    init {
        registerApplicationObserver(application)
    }

    override fun emitActivity(activity: Activity?) {
        if (currentActivityRef.get() != activity) {
            currentActivityRef = WeakReference(activity)
            resumedActivityFlow.tryEmit(currentActivityRef)
        }
    }

    private fun registerApplicationObserver(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) = emitActivity(activity)
                override fun onActivityPaused(activity: Activity) = clearIfCurrent(activity)
                override fun onActivityDestroyed(activity: Activity) = clearIfCurrent(activity)
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            }
        )
    }

    private fun clearIfCurrent(activity: Activity) {
        if (activity == currentActivityRef.get()) {
            emitActivity(null)
        }
    }
}
