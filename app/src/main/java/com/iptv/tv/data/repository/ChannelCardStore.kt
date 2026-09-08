package com.iptv.tv.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.iptv.tv.domain.model.ChannelSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Netflix-style 16:9 Home tiles from channel logos. Work stays on IO;
 * results are PNG files under files/channel-cards/.
 */
@Singleton
class ChannelCardStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val logoResolver: ChannelLogoResolver,
) {
    private val cacheDir: File
        get() = File(context.filesDir, "channel-cards").also { it.mkdirs() }

    private val familyIndex = ConcurrentHashMap<String, String>()
    private val indexLock = Any()

    @Volatile
    private var indexLoaded = false

    /**
     * Main-thread safe: returns an existing PNG under files/channel-cards/ or null.
     * Does not download or render.
     */
    fun cachedFile(
        url: String?,
        name: String,
        epgChannelId: String? = null,
    ): File? {
        ensureIndexLoaded()
        familyFile(name)?.let { return it }
        val candidates = logoResolver.candidates(
            name = name,
            epgChannelId = epgChannelId,
            existing = url,
        )
        for (candidate in candidates) {
            val cached = fileFor(candidate, name)
            if (cached.exists() && cached.length() > 32L) {
                rememberFamily(name, cached)
                return cached
            }
        }
        val missing = fileFor(null, name)
        if (missing.exists() && missing.length() > 32L) {
            rememberFamily(name, missing)
            return missing
        }
        return null
    }

    suspend fun ensureCard(
        url: String?,
        name: String,
        epgChannelId: String? = null,
        streamId: Int? = null,
    ): File = withContext(Dispatchers.IO) {
        cachedFile(url, name, epgChannelId)?.let { return@withContext it }
        val candidates = logoResolver.candidates(
            name = name,
            epgChannelId = epgChannelId,
            existing = url,
        )
        val missing = fileFor(null, name)
        if (candidates.isEmpty() && missing.exists() && missing.length() > 32L) {
            rememberFamily(name, missing)
            return@withContext missing
        }
        for (candidate in candidates) {
            val src = download(candidate) ?: continue
            logoResolver.persistIfChanged(streamId, url, candidate)
            val card = renderLogo(src, name)
            src.recycle()
            val dest = fileFor(candidate, name)
            writePng(dest, card)
            card.recycle()
            rememberFamily(name, dest, persist = true)
            return@withContext dest
        }
        if (missing.exists() && missing.length() > 32L) {
            rememberFamily(name, missing, persist = true)
            return@withContext missing
        }
        val placeholder = renderPlaceholder(name)
        writePng(missing, placeholder)
        placeholder.recycle()
        rememberFamily(name, missing, persist = true)
        missing
    }

    private fun familyKeyOf(name: String): String =
        ChannelSource.familyKey(name).ifBlank { ChannelSource.normalizeName(name) }

    private fun familyFile(name: String): File? {
        val key = familyKeyOf(name)
        if (key.isBlank()) return null
        val filename = familyIndex[key] ?: return null
        val file = File(cacheDir, filename)
        return if (file.exists() && file.length() > 32L) file else null
    }

    private fun rememberFamily(name: String, file: File, persist: Boolean = false) {
        val key = familyKeyOf(name)
        if (key.isBlank() || !file.exists()) return
        if (familyIndex.put(key, file.name) == file.name) return
        if (persist) persistIndex()
    }

    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        synchronized(indexLock) {
            if (indexLoaded) return
            val index = File(cacheDir, FAMILY_INDEX)
            if (index.exists()) {
                runCatching {
                    index.forEachLine { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            familyIndex[line.substring(0, tab)] = line.substring(tab + 1)
                        }
                    }
                }
            }
            indexLoaded = true
        }
    }

    private fun persistIndex() {
        runCatching {
            val index = File(cacheDir, FAMILY_INDEX)
            val tmp = File(cacheDir, "$FAMILY_INDEX.tmp")
            tmp.writeText(familyIndex.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            if (!tmp.renameTo(index)) {
                tmp.copyTo(index, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun fileFor(url: String?, name: String): File {
        val family = ChannelSource.familyKey(name).ifBlank { ChannelSource.normalizeName(name) }
        val src = url?.trim().orEmpty().ifBlank { "none" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$family|$src|${ChannelCardLayout.WIDTH}x${ChannelCardLayout.HEIGHT}|v1".toByteArray())
            .joinToString("") { b -> "%02x".format(b) }
            .take(32)
        return File(cacheDir, "$digest.png")
    }

    private fun download(url: String): Bitmap? {
        if (!ChannelSource.isUsableLogoUrl(url)) return null
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                decodeBounded(bytes)
            }
        }.getOrNull()
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun renderLogo(src: Bitmap, name: String): Bitmap {
        val out = Bitmap.createBitmap(
            ChannelCardLayout.WIDTH,
            ChannelCardLayout.HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        val opaque = opaqueRatio(src)
        canvas.drawColor(mixFill(sampleColor(src)))
        val dest = ChannelCardLayout.destRect(src.width, src.height, opaque)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            src,
            null,
            Rect(dest.left, dest.top, dest.right, dest.bottom),
            paint,
        )
        if (name.isBlank()) return out
        return out
    }

    private fun renderPlaceholder(name: String): Bitmap {
        val out = Bitmap.createBitmap(
            ChannelCardLayout.WIDTH,
            ChannelCardLayout.HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        canvas.drawColor(CHARCOAL)
        drawTvGlyph(canvas)
        val label = name.trim().ifBlank { "Channel" }
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 242, 242, 245)
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val y = ChannelCardLayout.HEIGHT * 0.72f
        val maxWidth = ChannelCardLayout.WIDTH * 0.86f
        val shown = ellipsize(label, textPaint, maxWidth)
        canvas.drawText(shown, ChannelCardLayout.WIDTH / 2f, y, textPaint)
        return out
    }

    private fun drawTvGlyph(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 138, 138, 150)
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeJoin = Paint.Join.ROUND
        }
        val cx = ChannelCardLayout.WIDTH / 2f
        val top = ChannelCardLayout.HEIGHT * 0.22f
        val body = RectF(cx - 54f, top, cx + 54f, top + 68f)
        canvas.drawRoundRect(body, 10f, 10f, paint)
        canvas.drawLine(cx, body.bottom + 6f, cx, body.bottom + 22f, paint)
        canvas.drawLine(cx - 22f, body.bottom + 22f, cx + 22f, body.bottom + 22f, paint)
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun opaqueRatio(bitmap: Bitmap): Float {
        val stepX = maxOf(1, bitmap.width / 24)
        val stepY = maxOf(1, bitmap.height / 24)
        var opaque = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                total++
                if (Color.alpha(bitmap.getPixel(x, y)) > 16) opaque++
                x += stepX
            }
            y += stepY
        }
        return if (total == 0) 1f else opaque.toFloat() / total
    }

    private fun sampleColor(bitmap: Bitmap): Int {
        val stepX = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 80) {
                    r += Color.red(pixel)
                    g += Color.green(pixel)
                    b += Color.blue(pixel)
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return CHARCOAL
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun mixFill(sampled: Int): Int {
        val sr = Color.red(sampled)
        val sg = Color.green(sampled)
        val sb = Color.blue(sampled)
        val cr = Color.red(CHARCOAL)
        val cg = Color.green(CHARCOAL)
        val cb = Color.blue(CHARCOAL)
        return Color.rgb(
            (sr * 0.18f + cr * 0.82f).toInt().coerceIn(0, 255),
            (sg * 0.18f + cg * 0.82f).toInt().coerceIn(0, 255),
            (sb * 0.18f + cb * 0.82f).toInt().coerceIn(0, 255),
        )
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.take(end) + ellipsis) > maxWidth) {
            end--
        }
        return text.take(end.coerceAtLeast(1)) + ellipsis
    }

    private companion object {
        const val USER_AGENT = "1VIEW-TV"
        const val CHARCOAL = 0xFF07111F.toInt()
        const val FAMILY_INDEX = "family-index.v1.txt"

        fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            val maxEdge = maxOf(width, height)
            val target = maxOf(ChannelCardLayout.WIDTH, ChannelCardLayout.HEIGHT) * 2
            while (maxEdge / (sample * 2) >= target) sample *= 2
            return sample
        }
    }
}
