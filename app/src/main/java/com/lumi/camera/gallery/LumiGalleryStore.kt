package com.lumi.camera.gallery

import android.content.ContentUris
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal data class GalleryPhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
) {
    fun formattedDate(): String {
        if (dateAddedSec <= 0L) return ""
        return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(dateAddedSec * 1000L))
    }
}

internal data class PhotoExifInfo(
    val aperture: String? = null,
    val shutter: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val dimensions: String? = null,
) {
    fun summaryLine(): String = buildList {
        aperture?.let(::add)
        shutter?.let(::add)
        iso?.let(::add)
        focalLength?.let(::add)
        dimensions?.let(::add)
    }.joinToString("  ·  ").ifBlank { "无拍摄参数" }
}

internal object LumiGalleryStore {
    private const val MAX_ITEMS = 120

    fun loadPhotos(context: Context): List<GalleryPhotoItem> {
        val resolver = context.contentResolver
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()

        val selection = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%Pictures/Lumi%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

        val items = mutableListOf<GalleryPhotoItem>()
        runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val wCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                while (cursor.moveToNext() && items.size < MAX_ITEMS) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else "Lumi.jpg"
                    val dateSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val w = if (wCol >= 0) cursor.getInt(wCol) else 0
                    val h = if (hCol >= 0) cursor.getInt(hCol) else 0
                    items += GalleryPhotoItem(id, uri, name, dateSec, w, h)
                }
            }
        }
        return items
    }

    fun readExif(context: Context, item: GalleryPhotoItem): PhotoExifInfo {
        val raw = runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                val exif = ExifInterface(stream)
                formatExif(
                    fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                    exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                    iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
                    focal35mm = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
                    focalMm = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, item.width),
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, item.height),
                )
            }
        }.getOrNull()
        return raw ?: PhotoExifInfo(
            dimensions = if (item.width > 0 && item.height > 0) "${item.width}×${item.height}" else null,
        )
    }

    internal fun formatExif(
        fNumber: String?,
        exposureTime: String?,
        iso: String?,
        focal35mm: String?,
        focalMm: String?,
        width: Int,
        height: Int,
    ): PhotoExifInfo {
        val aperture = parseRationalOrDouble(fNumber)?.takeIf { it > 0.5 }?.let {
            String.format(Locale.ROOT, "f/%.2f", it).trimEnd('0').trimEnd('.')
        }
        val shutter = parseRationalOrDouble(exposureTime)?.takeIf { it > 0.0 }?.let { sec ->
            if (sec >= 0.5) {
                String.format(Locale.ROOT, "%.1fs", sec).removeSuffix(".0s").let { if (it.endsWith("s")) it else "${it}s" }
            } else {
                "1/${(1.0 / sec).roundToInt().coerceAtLeast(1)}s"
            }
        }
        val isoText = iso?.trim()?.split(",")?.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }?.let { "ISO $it" }
        val focal = focal35mm?.toIntOrNull()?.takeIf { it in 8..400 }?.let { "${it}mm" }
            ?: parseRationalOrDouble(focalMm)?.takeIf { it > 0.5 }?.let {
                String.format(Locale.ROOT, "%.1fmm", it).removeSuffix(".0mm").let { s -> if (s.endsWith("mm")) s else "${s}mm" }
            }
        val dim = if (width > 0 && height > 0) "${width}×${height}" else null
        return PhotoExifInfo(
            aperture = aperture,
            shutter = shutter,
            iso = isoText,
            focalLength = focal,
            dimensions = dim,
        )
    }

    internal fun parseRationalOrDouble(raw: String?): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slash = s.indexOf('/')
        if (slash > 0) {
            val num = s.substring(0, slash).toDoubleOrNull() ?: return null
            val den = s.substring(slash + 1).toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
            return num / den
        }
        return s.toDoubleOrNull()
    }

    fun deletePhoto(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
}
