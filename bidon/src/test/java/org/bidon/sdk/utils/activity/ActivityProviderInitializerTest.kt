package org.bidon.sdk.utils.activity

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlin.test.Test

class ActivityProviderInitializerTest {

    private val provider = ActivityProviderImpl()

    @Test
    fun `onCreate installs the provider on the application`() {
        val application = mockk<Application>(relaxed = true) {
            every { applicationContext } returns this@mockk
        }
        val initializer = spyk(ActivityProviderInitializer(provider)) {
            every { context } returns application
        }

        val created = initializer.onCreate()

        assertThat(created).isTrue()
        verify(exactly = 1) { application.registerActivityLifecycleCallbacks(provider) }
    }

    @Test
    fun `onCreate does nothing when the context is not an application`() {
        val context = mockk<Context>(relaxed = true)
        val initializer = spyk(ActivityProviderInitializer(provider)) {
            every { this@spyk.context } returns context
        }

        val created = initializer.onCreate()

        assertThat(created).isTrue()
        assertThat(provider.install(mockk(relaxed = true))).isTrue()
    }

    @Test
    fun `onCreate does nothing when there is no context`() {
        val initializer = spyk(ActivityProviderInitializer(provider)) {
            every { context } returns null
        }

        assertThat(initializer.onCreate()).isTrue()
        assertThat(provider.install(mockk(relaxed = true))).isTrue()
    }
}
