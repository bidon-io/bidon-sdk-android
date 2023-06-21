package org.bidon.sdk.regulation

/**
 * Created by Aleksei Cherniaev on 21/06/2023.
 */
interface Regulation {
    var coppa: Coppa
    var gdpr: Gdpr
    var gdprConsentString: String?
    var usPrivacyString: String?
}

internal class RegulationImpl : Regulation {
    override var coppa: Coppa = Coppa.Default
    override var gdpr: Gdpr = Gdpr.Default

    override var gdprConsentString: String? = null
    override var usPrivacyString: String? = null
}