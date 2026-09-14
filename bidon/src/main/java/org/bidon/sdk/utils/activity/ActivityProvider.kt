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

/**
 * Tracks live Activities through [Application.ActivityLifecycleCallbacks].
 *
 * Android does not replay lifecycle callbacks for Activities that were resumed before registration,
 * so the process-wide [shared] instance is installed by [ActivityProviderInitializer] at process start.
 * [org.bidon.sdk.utils.di.DI.init] additionally seeds it from the initialization context and installs
 * it again (a no-op when already installed) for apps that removed the initializer from their manifest.
 */
internal class ActivityProviderImpl : ActivityProvider, Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)
    private val lock = Any()

    /** Known live Activities, ordered by the time they were last resumed or seeded, most recent last. */
    private val known = ArrayList<Entry>()

    override val resumedActivity: Activity?
        get() = latest { it.isResumed }

    override fun resolve(context: Context): Activity? =
        context.findActivity()?.takeIf { it.isAlive }
            ?: resumedActivity
            ?: latest { true }

    /**
     * Registers this provider once per instance. Returns true only for the call that registered.
     */
    fun install(application: Application): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        application.registerActivityLifecycleCallbacks(this)
        return true
    }

    /**
     * Records the Activity wrapped by [context], if any, as a known Activity, unless it is already tracked.
     */
    fun seed(context: Context) {
        val activity = context.findActivity()?.takeIf { it.isAlive } ?: return
        synchronized(lock) {
            if (known.none { it.activity === activity }) {
                known.add(Entry(activity, isResumed = false))
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(lock) {
            known.removeAll { it.activity == null || it.activity === activity }
            known.add(Entry(activity, isResumed = true))
        }
    }

    override fun onActivityPaused(activity: Activity) {
        synchronized(lock) {
            known.firstOrNull { it.activity === activity }?.isResumed = false
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        synchronized(lock) {
            known.removeAll { it.activity == null || it.activity === activity }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private inline fun latest(predicate: (Entry) -> Boolean): Activity? = synchronized(lock) {
        known.asReversed().firstNotNullOfOrNull { entry ->
            entry.activity?.takeIf { predicate(entry) && it.isAlive }
        }
    }

    private class Entry(activity: Activity, var isResumed: Boolean) {
        private val ref = WeakReference(activity)
        val activity: Activity? get() = ref.get()
    }

    companion object {
        /** The process-wide instance shared by [ActivityProviderInitializer] and DI. */
        val shared: ActivityProviderImpl by lazy { ActivityProviderImpl() }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val Activity.isAlive: Boolean
    get() = !isFinishing && !isDestroyed
