package org.bidon.sdk.segment.impl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.bidon.sdk.segment.models.Gender
import org.bidon.sdk.segment.Segment
import org.bidon.sdk.segment.models.SegmentAttributes
import org.bidon.sdk.segment.SegmentSynchronizer


/**
 * Created by Aleksei Cherniaev on 15/06/2023.
 */
internal class SegmentImpl : Segment, SegmentSynchronizer {
    private var attributesFlow = MutableStateFlow(SegmentAttributes.Empty)

    override val attributes: SegmentAttributes
        get() = attributesFlow.value

    override var segmentId: String? = null
        private set

    override fun setAge(age: Int?) {
        attributesFlow.value = attributesFlow.value.copy(
            age = age
        )
    }

    override fun setGender(gender: Gender?) {
        attributesFlow.value = attributesFlow.value.copy(
            gender = gender
        )
    }

    override fun putCustomAttribute(attribute: String, value: Any?) {
        this.attributesFlow.update { current ->
            current.copy(
                customAttributes = current.customAttributes
                    .toMutableMap()
                    .also {
                        if (value == null) {
                            it.remove(attribute)
                        } else {
                            it[attribute] = value
                        }
                    }
            )
        }
    }

    override fun setCustomAttributes(attributes: Map<String, Any>) {
        this.attributesFlow.value = this.attributesFlow.value.copy(
            customAttributes = attributes
        )
    }

    override fun setLevel(level: Int) {
        attributesFlow.value = attributesFlow.value.copy(
            gameLevel = level
        )
    }

    override fun setInAppAmount(inAppAmount: Int) {
        attributesFlow.value = attributesFlow.value.copy(
            inAppAmount = inAppAmount
        )
    }

    override fun setPaying(isPaying: Boolean) {
        attributesFlow.value = attributesFlow.value.copy(
            isPaying = isPaying
        )
    }

    override fun setSegmentId(segmentId: String) {
        this.segmentId = segmentId
    }
}