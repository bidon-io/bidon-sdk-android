package org.bidon.sdk.logs.logging.impl

import android.util.Log
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.logs.logging.Logger
import org.bidon.sdk.logs.logging.Logger.Level

/**
 * Created by Bidon Team on 06/02/2023.
 *
 * Set log level with [Logger]
 */
fun logInfo(tag: String, message: String) {
    if (BidonSdk.loggerLevel == Level.Verbose) {
        val thread = Thread.currentThread().name
        Log.d(DefaultTag, "[$thread] [$tag] $message")
    }
}

fun logError(tag: String, message: String, error: Throwable? = null) {
    if (BidonSdk.loggerLevel in arrayOf(Level.Error, Level.Verbose)) {
        val thread = Thread.currentThread().name
        Log.e(DefaultTag, "[$thread] [$tag] $message", error)
    }
}

private const val DefaultTag = "BidonLog"
