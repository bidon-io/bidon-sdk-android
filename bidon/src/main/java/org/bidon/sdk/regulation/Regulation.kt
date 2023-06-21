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
