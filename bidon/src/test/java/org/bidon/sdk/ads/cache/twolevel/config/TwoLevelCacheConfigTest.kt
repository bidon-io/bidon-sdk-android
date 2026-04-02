package org.bidon.sdk.ads.cache.twolevel.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

internal class TwoLevelCacheConfigTest {

    // -----------------------------------------------------------------------
    // DEFAULT values (spec §2)
    // -----------------------------------------------------------------------

    @Test
    fun `DEFAULT_FULLSCREEN - mainCacheSize is 2`() {
        assertThat(TwoLevelCacheConfig.DEFAULT_FULLSCREEN.mainCacheSize).isEqualTo(2)
    }

    @Test
    fun `DEFAULT_FULLSCREEN - fallbackCacheSize is 1`() {
        assertThat(TwoLevelCacheConfig.DEFAULT_FULLSCREEN.fallbackCacheSize).isEqualTo(1)
    }

    @Test
    fun `DEFAULT_FULLSCREEN - threshold is 80`() {
        assertThat(TwoLevelCacheConfig.DEFAULT_FULLSCREEN.threshold).isEqualTo(80)
    }

    @Test
    fun `DEFAULT_BANNER - mainCacheSize is 3`() {
        assertThat(TwoLevelCacheConfig.DEFAULT_BANNER.mainCacheSize).isEqualTo(3)
    }

    @Test
    fun `DEFAULT_BANNER - fallbackCacheSize is 5`() {
        assertThat(TwoLevelCacheConfig.DEFAULT_BANNER.fallbackCacheSize).isEqualTo(5)
    }

    @Test
    fun `DEFAULT_BANNER - threshold is 70`() {
        assertThat(TwoLevelCacheConfig.DEFAULT_BANNER.threshold).isEqualTo(70)
    }

    // -----------------------------------------------------------------------
    // Data class equality
    // -----------------------------------------------------------------------

    @Test
    fun `data class equality - same values are equal`() {
        val a = TwoLevelCacheConfig(mainCacheSize = 3, fallbackCacheSize = 2, threshold = 70)
        val b = TwoLevelCacheConfig(mainCacheSize = 3, fallbackCacheSize = 2, threshold = 70)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `data class equality - different values are not equal`() {
        val a = TwoLevelCacheConfig(mainCacheSize = 3, fallbackCacheSize = 2, threshold = 70)
        val b = TwoLevelCacheConfig(mainCacheSize = 3, fallbackCacheSize = 2, threshold = 80)
        assertThat(a).isNotEqualTo(b)
    }

    // -----------------------------------------------------------------------
    // Config ranges (spec §2)
    // -----------------------------------------------------------------------

    @Test
    fun `config - mainCacheSize range min 1`() {
        val config = TwoLevelCacheConfig(mainCacheSize = 1, fallbackCacheSize = 0, threshold = 0)
        assertThat(config.mainCacheSize).isEqualTo(1)
    }

    @Test
    fun `config - mainCacheSize range max 10`() {
        val config = TwoLevelCacheConfig(mainCacheSize = 10, fallbackCacheSize = 10, threshold = 100)
        assertThat(config.mainCacheSize).isEqualTo(10)
    }

    @Test
    fun `config - fallbackCacheSize 0 means disabled`() {
        val config = TwoLevelCacheConfig(mainCacheSize = 2, fallbackCacheSize = 0, threshold = 80)
        assertThat(config.fallbackCacheSize).isEqualTo(0)
    }

    @Test
    fun `config - threshold boundaries`() {
        val min = TwoLevelCacheConfig(mainCacheSize = 2, fallbackCacheSize = 1, threshold = 0)
        val max = TwoLevelCacheConfig(mainCacheSize = 2, fallbackCacheSize = 1, threshold = 100)
        assertThat(min.threshold).isEqualTo(0)
        assertThat(max.threshold).isEqualTo(100)
    }

    // -----------------------------------------------------------------------
    // Copy / destructuring
    // -----------------------------------------------------------------------

    @Test
    fun `copy - changes only specified field`() {
        val original = TwoLevelCacheConfig.DEFAULT_FULLSCREEN
        val modified = original.copy(threshold = 50)
        assertThat(modified.mainCacheSize).isEqualTo(original.mainCacheSize)
        assertThat(modified.fallbackCacheSize).isEqualTo(original.fallbackCacheSize)
        assertThat(modified.threshold).isEqualTo(50)
    }

    @Test
    fun `destructuring - all components match`() {
        val config = TwoLevelCacheConfig(mainCacheSize = 5, fallbackCacheSize = 3, threshold = 60)
        val (main, fallback, threshold) = config
        assertThat(main).isEqualTo(5)
        assertThat(fallback).isEqualTo(3)
        assertThat(threshold).isEqualTo(60)
    }
}
