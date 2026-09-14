package org.bidon.sdk.utils.activity

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Installs [ActivityProviderImpl.shared] at process start, before any Activity is created,
 * so that Activities resumed before [org.bidon.sdk.BidonSdk.initialize] is called are tracked.
 *
 * Declared in the SDK manifest; it stores nothing and serves no content.
 */
internal class ActivityProviderInitializer @JvmOverloads constructor(
    private val provider: ActivityProviderImpl = ActivityProviderImpl.shared
) : ContentProvider() {

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let(provider::install)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
