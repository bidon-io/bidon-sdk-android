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
     * The most recently resumed Activity that is currently in the resumed state, or null.
     * Supports multi-window, where several Activities can be resumed at once.
     */
    val resumedActivity: Activity?

    /**
     * Resolves an Activity for an ad load started with [context], in order of preference:
     * 1. the Activity wrapped by [context], when it is alive;
     * 2. [resumedActivity];
     * 3. the most recently resumed (or seeded) Activity that is still alive, even if it is paused,
     *    so that loads issued during Activity transitions or from the background still succeed.
     */
    fun resolve(context: Context): Activity?
}

internal class ActivityProviderImpl : ActivityProvider, Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)
    private val lock = Any()

    /** Activities currently in the resumed state, most recently resumed last. */
    private val resumed = ArrayList<WeakReference<Activity>>()

    /** The most recently resumed or seeded Activity; may already be paused or stopped. */
    @Volatile
    private var lastKnown: WeakReference<Activity>? = null

    override val resumedActivity: Activity?
        get() = synchronized(lock) {
            resumed.asReversed().firstNotNullOfOrNull { it.get()?.takeIf { activity -> activity.isAlive } }
        }

    override fun resolve(context: Context): Activity? =
        context.findActivity()?.takeIf { it.isAlive }
            ?: resumedActivity
            ?: lastKnown?.get()?.takeIf { it.isAlive }

    /**
     * Registers this provider once per instance. Returns true only for the call that registered.
     */
    fun install(application: Application): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        application.registerActivityLifecycleCallbacks(this)
        return true
    }

    /**
     * Records the Activity wrapped by [context], if any, as the last known Activity.
     * Lifecycle callbacks are not replayed for Activities resumed before [install],
     * so the SDK seeds the provider with the Activity it was initialized from.
     */
    fun seed(context: Context) {
        context.findActivity()?.takeIf { it.isAlive }?.let { lastKnown = WeakReference(it) }
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(lock) {
            resumed.removeAll { it.get() == null || it.get() === activity }
            resumed.add(WeakReference(activity))
        }
        lastKnown = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        synchronized(lock) {
            resumed.removeAll { it.get() == null || it.get() === activity }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        synchronized(lock) {
            resumed.removeAll { it.get() == null || it.get() === activity }
        }
        if (lastKnown?.get() === activity) {
            lastKnown = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
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
