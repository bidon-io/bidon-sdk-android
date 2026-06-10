package org.bidon.sdk.regulation

/**
 * Created by Bidon Team on 13/07/2023.
 */
public enum class Coppa(public val code: Int) {
    Unknown(code = -1),
    No(code = 0),
    Yes(code = 1);

    public companion object {
        public val Default: Coppa get() = Unknown
    }
}