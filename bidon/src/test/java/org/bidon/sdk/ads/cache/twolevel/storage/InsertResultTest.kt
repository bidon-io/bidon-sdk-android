package org.bidon.sdk.ads.cache.twolevel.storage

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.bidon.sdk.auction.models.AuctionResult
import org.junit.Test

internal class InsertResultTest {

    // -----------------------------------------------------------------------
    // isInserted
    // -----------------------------------------------------------------------

    @Test
    fun `Success without evicted - isInserted true`() {
        val result = InsertResult.Success()
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `Success with evicted - isInserted true`() {
        val evicted = mockk<AuctionResult>(relaxed = true)
        val result = InsertResult.Success(evicted = evicted)
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `Rejected Threshold - isInserted false`() {
        val result = InsertResult.Rejected(InsertResult.Reason.Threshold)
        assertThat(result.isInserted).isFalse()
    }

    @Test
    fun `Rejected StickyProtected - isInserted false`() {
        val result = InsertResult.Rejected(InsertResult.Reason.StickyProtected)
        assertThat(result.isInserted).isFalse()
    }

    @Test
    fun `Rejected CacheFull - isInserted false`() {
        val result = InsertResult.Rejected(InsertResult.Reason.CacheFull)
        assertThat(result.isInserted).isFalse()
    }

    // -----------------------------------------------------------------------
    // Success.evicted
    // -----------------------------------------------------------------------

    @Test
    fun `Success default evicted is null`() {
        val result = InsertResult.Success()
        assertThat(result.evicted).isNull()
    }

    @Test
    fun `Success with evicted stores the reference`() {
        val evicted = mockk<AuctionResult>(relaxed = true)
        val result = InsertResult.Success(evicted = evicted)
        assertThat(result.evicted).isSameInstanceAs(evicted)
    }

    // -----------------------------------------------------------------------
    // Equality
    // -----------------------------------------------------------------------

    @Test
    fun `Rejected equality - same reason are equal`() {
        val a = InsertResult.Rejected(InsertResult.Reason.Threshold)
        val b = InsertResult.Rejected(InsertResult.Reason.Threshold)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `Rejected equality - different reasons are not equal`() {
        val a = InsertResult.Rejected(InsertResult.Reason.Threshold)
        val b = InsertResult.Rejected(InsertResult.Reason.CacheFull)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `Success equality - both null evicted are equal`() {
        assertThat(InsertResult.Success()).isEqualTo(InsertResult.Success())
    }

    // -----------------------------------------------------------------------
    // Reason enum
    // -----------------------------------------------------------------------

    @Test
    fun `Reason values - all three present`() {
        val values = InsertResult.Reason.entries
        assertThat(values).hasSize(3)
        assertThat(values).containsExactly(
            InsertResult.Reason.Threshold,
            InsertResult.Reason.StickyProtected,
            InsertResult.Reason.CacheFull,
        )
    }
}
