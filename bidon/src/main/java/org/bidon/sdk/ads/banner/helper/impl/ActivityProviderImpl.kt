package org.bidon.sdk.ads.banner.helper.impl

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.bidon.sdk.ads.banner.helper.ActivityProvider
import java.lang.ref.WeakReference

internal class ActivityProviderImpl(application: Application) : ActivityProvider {

    override val resumedActivityFlow = MutableSharedFlow<WeakReference<Activity>>(replay = 1)
    private var currentActivityRef: WeakReference<Activity>? = null

    init {
        registerApplicationObserver(application)
    }

    override fun emitActivity(activity: Activity?) {
        if (currentActivityRef?.get() != activity) {
            currentActivityRef = WeakReference(activity)
            resumedActivityFlow.tryEmit(currentActivityRef ?: WeakReference(null))
        }
    }

    override suspend fun awaitResumedActivity(): Activity {
        return resumedActivityFlow.first { it.get() != null }.get()
            ?: awaitResumedActivity()
    }

    private fun registerApplicationObserver(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    emitActivity(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (activity == currentActivityRef?.get()) {
                        emitActivity(null)
                    }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity == currentActivityRef?.get()) {
                        emitActivity(null)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            }
        )
    }
}
