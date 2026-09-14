package org.bidon.sdk.utils.activity

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides the Activity that ad networks require for loading when the publisher only supplies a [Context].
 */
internal interface ActivityProvider {
    /**
     * The most recently resumed Activity that has not been finished or destroyed, or null.
     */
    val resumedActivity: Activity?

    /**
     * Resolves an Activity for an ad load started with [context]:
     * the Activity wrapped by [context] when it is alive, otherwise [resumedActivity].
     */
    fun resolve(context: Context): Activity?
}

internal class ActivityProviderImpl : ActivityProvider, Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    override val resumedActivity: Activity?
        get() = resumed?.get()?.takeIf { it.isAlive }

    override fun resolve(context: Context): Activity? =
        context.findActivity()?.takeIf { it.isAlive } ?: resumedActivity

    /**
     * Registers this provider once per instance. Returns true only for the call that registered.
     */
    fun install(application: Application): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        application.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) {
            resumed = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val Activity.isAlive: Boolean
    get() = !isFinishing && !isDestroyed
