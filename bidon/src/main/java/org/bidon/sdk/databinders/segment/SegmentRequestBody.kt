package org.bidon.sdk.databinders.segment

import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable

/**
 * Created by Bidon Team on 13/07/2023.
 */
internal data class SegmentRequestBody(
    @field:JsonName("uid")
    val uid: String?,
    /**
     * JSON Encoded String of [SegmentAttributesRequestBody]
     */
    @field:JsonName("ext")
    val ext: String?,
) : Serializable
