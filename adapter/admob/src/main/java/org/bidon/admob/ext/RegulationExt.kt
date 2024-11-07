package org.bidon.admob.ext

import android.os.Bundle
import org.bidon.admob.AdmobDemandId
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation

/**
 * Created by Bidon Team on 22/06/2023.
 */
internal fun Regulation.asBundle() = Bundle().apply {
    val regulation = this@asBundle
    logInfo(
        "AdmobAdapter",
        "Applying regulation to ${AdmobDemandId.demandId} <- " +
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