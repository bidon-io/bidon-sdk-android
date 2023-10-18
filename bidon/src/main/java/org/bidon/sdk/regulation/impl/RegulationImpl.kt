package org.bidon.sdk.regulation.impl

import org.bidon.sdk.regulation.Coppa
import org.bidon.sdk.regulation.Gdpr
import org.bidon.sdk.regulation.Iab
import org.bidon.sdk.regulation.IabConsent
import org.bidon.sdk.regulation.Regulation

internal class RegulationImpl(
    private val iabConsent: IabConsent
) : Regulation {
    override var coppa: Coppa = Coppa.Default
    override var gdpr: Gdpr = Gdpr.Default

    override var gdprConsentString: String? = null
    override var usPrivacyString: String? = null

    override val iab: Iab
        get() = iabConsent.iab
}