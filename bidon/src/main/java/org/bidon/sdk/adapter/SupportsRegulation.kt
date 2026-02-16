package org.bidon.sdk.adapter

import org.bidon.sdk.regulation.Regulation

/**
 * Created by Bidon Team on 13/07/2023.
 */
public interface SupportsRegulation {
    public fun updateRegulation(regulation: Regulation)
}