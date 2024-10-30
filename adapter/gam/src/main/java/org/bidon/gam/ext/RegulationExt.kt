package org.bidon.gam.ext

import android.os.Bundle
import org.bidon.gam.GamDemandId
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation

internal fun Regulation.asBundle() = Bundle().apply {
    val regulation = this@asBundle
    logInfo(
        "GamAdapter",
        "Applying regulation to ${GamDemandId.demandId} <- " +
                "GDPR=${regulation.gdpr}, " +
                "COPPA=${regulation.coppa}, " +
                "usPrivacyString=${regulation.usPrivacyString}, " +
                "gdprConsentString=${regulation.gdprConsentString}"
    )

    regulation.usPrivacyString?.let {
        putString("IABUSPrivacy_String", it)
    }
    regulation.gdprConsentString?.let {
        putString("IABConsent_ConsentString", it)
    }
    putBoolean("IABConsent_SubjectToGDPR", regulation.gdprApplies)
}