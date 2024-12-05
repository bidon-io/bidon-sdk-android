package org.bidon.sdk.utils.ext

/**
 * Created by Bidon Team on 05/09/2023.
 */
internal val Any.TAG: String
    get() = this::class.java.simpleName