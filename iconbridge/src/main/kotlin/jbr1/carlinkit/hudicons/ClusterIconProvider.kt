package jbr1.carlinkit.hudicons

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Claims `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`.
 *
 * GM's Templates Host references this authority when converting CarIcon maneuver icons into
 * cluster/HUD nav state, but never registers a provider for it. The first failed insert()
 * latches `skipIcons = true` for the session — HUD shows road + distance, no turn glyph.
 *
 * Play Console locks that authority to its first publisher (upstream `zeno.carlink`,
 * lvalen91/carlink#6), so the Play-distributed `jbr1.carlinkit` app cannot declare it.
 * This sideloaded companion can. The host resolves by authority string only; it does not
 * care which package owns the provider (same approach as enigy/Carlink-legacy's bridge).
 *
 * Contract:
 * - insert(): store PNG bytes, return a content URI (must never return null)
 * - query(selection = iconId): return contentUri + aspectRatio
 * - openFile(): serve PNG via pipe (optional ?w=&h= scaling)
 *
 * URIs are content-addressed (`png_<sha256-prefix>`) so an unchanged icon keeps a stable URI
 * across the host's per-tick re-inserts (avoids HUD flicker).
 */
class ClusterIconProvider : ContentProvider() {
    private val pngByContent: MutableMap<String, ByteArray> = lruMap(MAX_CONTENT_ENTRIES)
    private val contentByIconId: MutableMap<String, String> = lruMap(MAX_ICON_ID_ENTRIES)
    private val scaledPng: MutableMap<String, ByteArray> = lruMap(MAX_SCALED_ENTRIES)

    override fun onCreate(): Boolean {
        Log.i(TAG, "Cluster icon provider registered ($AUTHORITY)")
        return true
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        val iconId = values?.getAsString("iconId")
        val data = values?.getAsByteArray("data")
        if (iconId == null || data == null) {
            Log.w(TAG, "insert() missing iconId or data (iconId=$iconId, dataSize=${data?.size})")
            return Uri.parse("content://$AUTHORITY/img/unknown")
        }
        val contentKey = contentKey(data)
        pngByContent[contentKey] = data
        contentByIconId[iconId] = contentKey
        return contentUri(contentKey)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("contentUri", "aspectRatio"))
        if (selection == null) return cursor

        val contentKey = contentByIconId[selection]
        val data = contentKey?.let { pngByContent[it] }
        if (contentKey == null || data == null) return cursor

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val aspectRatio = if (opts.outHeight > 0) opts.outWidth.toDouble() / opts.outHeight else 1.0
        cursor.addRow(arrayOf<Any>(contentUri(contentKey).toString(), aspectRatio))
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val contentKey = uri.lastPathSegment
        val data = contentKey?.let { pngByContent[it] }
        if (contentKey == null || data == null) {
            Log.w(TAG, "openFile() cache miss for $contentKey")
            return null
        }

        val targetW = uri.getQueryParameter("w")?.toIntOrNull()
        val targetH = uri.getQueryParameter("h")?.toIntOrNull()
        val output =
            if (targetW != null && targetH != null && targetW > 0 && targetH > 0) {
                val scaledKey = "$contentKey@${targetW}x$targetH"
                scaledPng[scaledKey] ?: scaleIcon(data, targetW, targetH).also { scaledPng[scaledKey] = it }
            } else {
                data
            }

        val pipe = ParcelFileDescriptor.createPipe()
        val writeEnd = pipe[1]
        Thread(
            {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(output) }
                } catch (e: Exception) {
                    Log.e(TAG, "openFile() pipe write error for $contentKey: ${e.message}")
                }
            },
            "HudIconPipe",
        ).start()
        return pipe[0]
    }

    private fun contentUri(contentKey: String): Uri = Uri.parse("content://$AUTHORITY/img/$contentKey")

    private fun contentKey(png: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(png)
        val hex = StringBuilder("png_")
        for (i in 0 until 8) hex.append("%02x".format(digest[i]))
        return hex.toString()
    }

    private fun scaleIcon(
        png: ByteArray,
        w: Int,
        h: Int,
    ): ByteArray =
        try {
            val original = BitmapFactory.decodeByteArray(png, 0, png.size)
            if (original == null) {
                png
            } else {
                val scaled = Bitmap.createScaledBitmap(original, w, h, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (scaled !== original) scaled.recycle()
                original.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "scaleIcon(${w}x$h) failed: ${e.message} — serving original")
            png
        }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String = "image/png"

    private companion object {
        const val TAG = "CarlinkHudIcons"
        const val AUTHORITY =
            "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"
        const val MAX_CONTENT_ENTRIES = 20
        const val MAX_ICON_ID_ENTRIES = 256
        const val MAX_SCALED_ENTRIES = 20

        fun <V> lruMap(maxEntries: Int): MutableMap<String, V> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, V>(maxEntries, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
                        size > maxEntries
                },
            )
    }
}
