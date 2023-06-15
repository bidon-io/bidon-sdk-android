package org.bidon.sdk.segment

import org.bidon.sdk.segment.models.SegmentAttributes


/**
 * Created by Aleksei Cherniaev on 15/06/2023.
 */
internal interface SegmentSynchronizer {
    val attributes: SegmentAttributes
    val segmentId: String?
    fun setSegmentId(segmentId: String)
}