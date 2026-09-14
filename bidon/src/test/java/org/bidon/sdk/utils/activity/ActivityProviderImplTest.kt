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
    fun `resumedActivity is kept when the activity is paused`() {
        val activity = activity()
        provider.onActivityResumed(activity)

        provider.onActivityPaused(activity)

        assertThat(provider.resumedActivity).isSameInstanceAs(activity)
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
