package org.bidon.sdk.utils.activity

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class ActivityProviderImplTest {

    private val provider = ActivityProviderImpl()

    @Test
    fun `resumedActivity is null before any activity is resumed`() {
        assertThat(provider.resumedActivity).isNull()
    }

    @Test
    fun `resumedActivity returns the last resumed activity`() {
        val first = activity()
        val second = activity()

        provider.onActivityResumed(first)
        provider.onActivityResumed(second)

        assertThat(provider.resumedActivity).isSameInstanceAs(second)
    }

    @Test
    fun `resumedActivity is null when the only resumed activity is paused`() {
        val activity = activity()
        provider.onActivityResumed(activity)

        provider.onActivityPaused(activity)

        assertThat(provider.resumedActivity).isNull()
    }

    @Test
    fun `resumedActivity returns the other resumed activity when one of two is paused`() {
        val first = activity()
        val second = activity()
        provider.onActivityResumed(first)
        provider.onActivityResumed(second)

        provider.onActivityPaused(second)

        assertThat(provider.resumedActivity).isSameInstanceAs(first)
    }

    @Test
    fun `resumedActivity returns the other resumed activity when one of two is destroyed`() {
        val first = activity()
        val second = activity()
        provider.onActivityResumed(first)
        provider.onActivityResumed(second)

        provider.onActivityDestroyed(second)

        assertThat(provider.resumedActivity).isSameInstanceAs(first)
    }

    @Test
    fun `resumedActivity does not duplicate an activity resumed twice`() {
        val first = activity()
        val second = activity()
        provider.onActivityResumed(first)
        provider.onActivityResumed(second)
        provider.onActivityResumed(first)

        provider.onActivityPaused(first)

        assertThat(provider.resumedActivity).isSameInstanceAs(second)
    }

    @Test
    fun `resumedActivity is null after the resumed activity is destroyed`() {
        val activity = activity()
        provider.onActivityResumed(activity)

        provider.onActivityDestroyed(activity)

        assertThat(provider.resumedActivity).isNull()
    }

    @Test
    fun `resumedActivity is kept when another activity is destroyed`() {
        val resumed = activity()
        provider.onActivityResumed(resumed)

        provider.onActivityDestroyed(activity())

        assertThat(provider.resumedActivity).isSameInstanceAs(resumed)
    }

    @Test
    fun `resumedActivity is null when the resumed activity is finishing`() {
        val activity = activity()
        provider.onActivityResumed(activity)

        every { activity.isFinishing } returns true

        assertThat(provider.resumedActivity).isNull()
    }

    @Test
    fun `resolve prefers the activity wrapped by the context`() {
        val resumed = activity()
        val wrapped = activity()
        provider.onActivityResumed(resumed)
        val context = mockk<ContextWrapper> { every { baseContext } returns wrapped }

        val resolved = provider.resolve(context)

        assertThat(resolved).isSameInstanceAs(wrapped)
    }

    @Test
    fun `resolve falls back to the resumed activity for an application context`() {
        val resumed = activity()
        provider.onActivityResumed(resumed)

        val resolved = provider.resolve(mockk<Context>())

        assertThat(resolved).isSameInstanceAs(resumed)
    }

    @Test
    fun `resolve ignores a finishing activity wrapped by the context`() {
        val resumed = activity()
        provider.onActivityResumed(resumed)
        val finishing = activity().also { every { it.isFinishing } returns true }

        val resolved = provider.resolve(finishing)

        assertThat(resolved).isSameInstanceAs(resumed)
    }

    @Test
    fun `resolve returns null when no activity is available`() {
        assertThat(provider.resolve(mockk<Context>())).isNull()
    }

    @Test
    fun `resolve falls back to the last resumed activity when it is paused`() {
        val activity = activity()
        provider.onActivityResumed(activity)
        provider.onActivityPaused(activity)

        val resolved = provider.resolve(mockk<Context>())

        assertThat(resolved).isSameInstanceAs(activity)
    }

    @Test
    fun `resolve returns null when the last resumed activity is destroyed`() {
        val activity = activity()
        provider.onActivityResumed(activity)
        provider.onActivityPaused(activity)
        provider.onActivityDestroyed(activity)

        assertThat(provider.resolve(mockk<Context>())).isNull()
    }

    @Test
    fun `resolve falls back to an earlier paused activity when a later one is destroyed`() {
        val paused = activity()
        val destroyed = activity()
        provider.onActivityResumed(paused)
        provider.onActivityPaused(paused)
        provider.onActivityResumed(destroyed)

        provider.onActivityPaused(destroyed)
        provider.onActivityDestroyed(destroyed)

        assertThat(provider.resolve(mockk<Context>())).isSameInstanceAs(paused)
    }

    @Test
    fun `resolve falls back to the seeded activity when a later resumed activity is destroyed`() {
        val seeded = activity()
        val destroyed = activity()
        provider.seed(seeded)
        provider.onActivityResumed(destroyed)

        provider.onActivityDestroyed(destroyed)

        assertThat(provider.resolve(mockk<Context>())).isSameInstanceAs(seeded)
    }

    @Test
    fun `resolve prefers the most recently resumed of two paused activities`() {
        val older = activity()
        val newer = activity()
        provider.onActivityResumed(older)
        provider.onActivityPaused(older)
        provider.onActivityResumed(newer)
        provider.onActivityPaused(newer)

        assertThat(provider.resolve(mockk<Context>())).isSameInstanceAs(newer)
    }

    @Test
    fun `resolve falls back to an older paused activity when the last resumed one is destroyed`() {
        val older = activity()
        val newer = activity()
        provider.onActivityResumed(older)
        provider.onActivityPaused(older)
        provider.onActivityResumed(newer)

        provider.onActivityDestroyed(newer)

        assertThat(provider.resolve(mockk<Context>())).isSameInstanceAs(older)
    }

    @Test
    fun `resolve skips a paused activity that became finishing`() {
        val older = activity()
        val newer = activity()
        provider.onActivityResumed(older)
        provider.onActivityPaused(older)
        provider.onActivityResumed(newer)
        provider.onActivityPaused(newer)

        every { newer.isFinishing } returns true

        assertThat(provider.resolve(mockk<Context>())).isSameInstanceAs(older)
    }

    @Test
    fun `resolve prefers a currently resumed activity over a paused one`() {
        val paused = activity()
        val resumed = activity()
        provider.onActivityResumed(paused)
        provider.onActivityPaused(paused)
        provider.onActivityResumed(resumed)

        val resolved = provider.resolve(mockk<Context>())

        assertThat(resolved).isSameInstanceAs(resumed)
    }

    @Test
    fun `seed makes the wrapped activity available before any lifecycle callback`() {
        val seeded = activity()
        val context = mockk<ContextWrapper> { every { baseContext } returns seeded }

        provider.seed(context)

        assertThat(provider.resumedActivity).isNull()
        assertThat(provider.resolve(mockk<Context>())).isSameInstanceAs(seeded)
    }

    @Test
    fun `seed ignores a context that does not wrap an activity`() {
        provider.seed(mockk<Context>())

        assertThat(provider.resolve(mockk<Context>())).isNull()
    }

    @Test
    fun `seed ignores a finishing activity`() {
        val finishing = activity().also { every { it.isFinishing } returns true }

        provider.seed(finishing)

        assertThat(provider.resolve(mockk<Context>())).isNull()
    }

    @Test
    fun `seed does not reset a tracked activity`() {
        val activity = activity()
        provider.onActivityResumed(activity)

        provider.seed(activity)

        assertThat(provider.resumedActivity).isSameInstanceAs(activity)
    }

    @Test
    fun `seeded activity is replaced by a later resumed activity`() {
        val seeded = activity()
        val resumed = activity()
        provider.seed(seeded)

        provider.onActivityResumed(resumed)
        provider.onActivityPaused(resumed)

        assertThat(provider.resolve(mockk<Context>())).isSameInstanceAs(resumed)
    }

    @Test
    fun `install registers lifecycle callbacks only once`() {
        val application = mockk<Application>(relaxed = true)

        val first = provider.install(application)
        val second = provider.install(application)

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        verify(exactly = 1) { application.registerActivityLifecycleCallbacks(provider) }
    }

    private fun activity(): Activity = mockk {
        every { isFinishing } returns false
        every { isDestroyed } returns false
    }
}
