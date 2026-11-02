package com.gnosh.tvhome

import android.content.ContentProvider
import android.content.ComponentName
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import tvhome.core.secrets.Provision
import tvhome.core.secrets.ProvisionCommand
import tvhome.core.secrets.ProvisionOutcome
import tvhome.core.secrets.SecretKey
import tvhome.core.secrets.SecretStore
import java.io.FileNotFoundException

/**
 * `adb shell content write --uri content://com.gnosh.tvhome.provision/calendar_url < value.txt` streams the value over
 * stdin, so it is never a command-line argument or a logged intent extra (`#10`). The manifest requires the DUMP
 * permission (adb shell only); [enforceShell] re-checks the caller's uid as defence in depth.
 *
 * - write: reads at most 1 KiB, strict UTF-8, trims, re-checks the regex, stores. The caller gets no text back.
 * - query: one row, column `configured` (0/1) and `last` (a fixed outcome name such as SAVED_CALENDAR). Never the value.
 * - delete: clears that secret. insert/update of a secret: refused (their values would travel as command-line arguments).
 * - Not secrets: `/location` (insert sets, delete clears the weather location) and `/home_entry` (insert turns the M3
 *   Home alias on, delete turns it off).
 */
class ProvisionProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        enforceShell()
        val key = keyOf(uri) ?: throw FileNotFoundException()
        if (!mode.startsWith("w")) throw FileNotFoundException()
        val store = secrets()
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = pipe[0]
        val writer = pipe[1]
        Thread({ consume(store, key, reader) }, "provision-write").apply { isDaemon = true }.start()
        return writer // the framework closes our copy once it has been handed to the caller
    }

    private fun consume(store: SecretStore, key: SecretKey, reader: ParcelFileDescriptor) {
        val outcome = try {
            ParcelFileDescriptor.AutoCloseInputStream(reader).use { input ->
                Provision.apply(store, Provision.fromStream(key, input))
            }
        } catch (_: Throwable) {
            ProvisionOutcome.FAILED
        }
        last[key] = outcome
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        enforceShell()
        val key = keyOf(uri) ?: throw FileNotFoundException()
        val configured = if (runCatching { secrets().get(key) }.getOrNull() != null) 1 else 0
        return MatrixCursor(arrayOf("configured", "last")).apply { addRow(arrayOf(configured, (last[key] ?: ProvisionOutcome.NOTHING).name)) }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceShell()
        if (uri.pathSegments == listOf("location")) { FeedSettings.clearLocation(context!!.applicationContext); return 1 }
        if (uri.pathSegments == listOf(HOME_ENTRY)) { setHomeEntry(false); return 1 }
        val key = keyOf(uri) ?: throw FileNotFoundException()
        last[key] = Provision.apply(secrets(), ProvisionCommand.Clear(key))
        return 1
    }

    /**
     * `content insert --uri content://com.gnosh.tvhome.provision/location --bind lat:d:36.53 --bind lon:d:-87.36`: the
     * hero's weather location. Not a secret, so it may travel as arguments. Anything else is refused.
     */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceShell()
        if (uri.pathSegments == listOf(HOME_ENTRY)) { setHomeEntry(true); return uri }
        if (uri.pathSegments != listOf("location")) throw UnsupportedOperationException()
        val v = values ?: throw IllegalArgumentException("values")
        val lat = v.getAsDouble("lat") ?: throw IllegalArgumentException("lat")
        val lon = v.getAsDouble("lon") ?: throw IllegalArgumentException("lon")
        if (!FeedSettings.setLocation(context!!.applicationContext, lat, lon)) throw IllegalArgumentException("range")
        return uri
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null

    private fun secrets(): SecretStore = (context!!.applicationContext as TvHomeApp).graph.secrets

    /** `/calendar_url` or `/anthropic_api_key`, nothing else. */
    private fun keyOf(uri: Uri): SecretKey? =
        if (uri.pathSegments.size == 1) Provision.keyOf(uri.pathSegments[0]) else null

    /**
     * M3: `content insert --uri content://com.gnosh.tvhome.provision/home_entry --bind on:i:1` turns the `.HomeEntry`
     * alias on, `content delete` on the same URI turns it off (scripts/tv-home-takeover.ps1). Since Android 13 the shell
     * may not change a release app's component state with `pm enable`, but the app may change its own.
     */
    private fun setHomeEntry(on: Boolean) {
        val ctx = context!!.applicationContext
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx.packageName, "${ctx.packageName}.HomeEntry"),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun enforceShell() {
        val uid = Binder.getCallingUid()
        // 2000 = shell, 0 = root (adb root on an emulator), own uid for in-process calls.
        if (uid != SHELL_UID && uid != 0 && uid != Process.myUid()) throw SecurityException("provision")
    }

    private companion object {
        const val SHELL_UID = 2000
        const val HOME_ENTRY = "home_entry"
        val last = java.util.concurrent.ConcurrentHashMap<SecretKey, ProvisionOutcome>()
    }
}
