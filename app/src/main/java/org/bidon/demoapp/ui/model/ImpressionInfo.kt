package org.bidon.demoapp.ui.model

data class ImpressionInfo(
    val adUnitName: String,
    val networkName: String,
    val placementId: String?,
    val placementName: String?,
    val revenue: Double,
    val currency: String?,
    val precision: String,
    val demandSource: String?,
    val ext: ImpExt,
) {
    fun toJson(): String {
        return """
            {
                "unit_name": "$adUnitName",
                "network_name": "$networkName",
                "placement_id": ${placementId?.let { "\"$it\"" }},
                "placement_name": ${placementName?.let { "\"$it\"" }},
                "revenue": $revenue,
                "currency": ${currency?.let { "\"$it\"" }},
                "precision": "$precision",
                "demand_source": ${demandSource?.let { "\"$it\"" }},
                "ext": ${ext.toJson()}
            }
        """.trimIndent()
    }
}

data class ImpExt(
    val networkName: String,
    val dspName: String?,
    val adUnitId: String,
    val credentials: String?,
) {
    fun toJson(): String {
        return """
            {
                "network_name": "$networkName",
                "dsp_name": ${dspName?.let { "\"$it\"" }},
                "ad_unit_id": "$adUnitId",
                "credentials": ${credentials?.let { "\"$it\"" }}
            }
        """.trimIndent()
    }
}