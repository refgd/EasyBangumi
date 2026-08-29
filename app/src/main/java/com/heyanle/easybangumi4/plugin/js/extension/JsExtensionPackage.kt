package com.heyanle.easybangumi4.plugin.js.extension

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.gson.Gson
import com.heyanle.easybangumi4.BuildConfig
import com.heyanle.easybangumi4.utils.aesEncryptTo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object JsExtensionPackage {
    const val SUFFIX = "ebg.pkg"
    const val MIME_TYPE = "application/vnd.easybangumi.package"
    const val ICON_SUFFIX = "icon"
    const val MAX_ICON_BYTES = 4 * 1024 * 1024

    private const val FORMAT_VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val EXTENSION_ENTRY = "extension.ebg.jsc"
    private const val ICON_ENTRY = "icon"
    private const val MAX_EXTENSION_BYTES = 4 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    data class Manifest(
        val formatVersion: Int = FORMAT_VERSION,
        val key: String = "",
        val label: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        val extension: String = EXTENSION_ENTRY,
        val icon: String = ICON_ENTRY,
    )

    data class Extracted(
        val manifest: Manifest,
        val extensionFile: File,
        val iconFile: File,
    )

    fun isPackageName(name: String): Boolean = name.endsWith(SUFFIX, ignoreCase = true)

    fun localIconFile(extensionFolder: File, key: String): File =
        File(extensionFolder, "$key.$ICON_SUFFIX")

    fun write(
        output: OutputStream,
        manifest: Manifest,
        encryptedExtension: File,
        iconBytes: ByteArray,
    ) {
        if (!encryptedExtension.isFile || encryptedExtension.length() !in 1..MAX_EXTENSION_BYTES.toLong()) {
            throw IOException("加密插件文件无效")
        }
        validateIcon(iconBytes)
        ZipOutputStream(output.buffered()).use { zip ->
            writeEntry(zip, MANIFEST_ENTRY, Gson().toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.putNextEntry(ZipEntry(EXTENSION_ENTRY))
            encryptedExtension.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            writeEntry(zip, ICON_ENTRY, iconBytes)
        }
    }

    fun extract(input: InputStream, outputFolder: File): Extracted {
        outputFolder.deleteRecursively()
        outputFolder.mkdirs()
        val files = mutableMapOf<String, File>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name !in setOf(MANIFEST_ENTRY, EXTENSION_ENTRY, ICON_ENTRY)) {
                    throw IOException("插件包包含不支持的条目: ${entry.name}")
                }
                if (files.containsKey(entry.name)) throw IOException("插件包条目重复: ${entry.name}")
                val limit = when (entry.name) {
                    MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                    EXTENSION_ENTRY -> MAX_EXTENSION_BYTES
                    else -> MAX_ICON_BYTES
                }
                val target = File(outputFolder, entry.name)
                target.outputStream().use { copyLimited(zip, it, limit) }
                files[entry.name] = target
                zip.closeEntry()
            }
        }
        val manifestFile = files[MANIFEST_ENTRY] ?: throw IOException("插件包缺少 $MANIFEST_ENTRY")
        val extensionFile = files[EXTENSION_ENTRY] ?: throw IOException("插件包缺少 $EXTENSION_ENTRY")
        val iconFile = files[ICON_ENTRY] ?: throw IOException("插件包缺少 $ICON_ENTRY")
        val manifest = runCatching { Gson().fromJson(manifestFile.readText(), Manifest::class.java) }
            .getOrElse { throw IOException("插件包清单无效", it) }
        if (manifest.formatVersion != FORMAT_VERSION ||
            manifest.extension != EXTENSION_ENTRY || manifest.icon != ICON_ENTRY ||
            manifest.key.isBlank()
        ) {
            throw IOException("插件包清单不受支持")
        }
        validateIcon(iconFile.readBytes())
        return Extracted(manifest, extensionFile, iconFile)
    }

    fun encrypt(sourceFile: File, outputFile: File) {
        if (sourceFile.name.endsWith(".ebg.jsc", ignoreCase = true)) {
            sourceFile.copyTo(outputFile, overwrite = true)
            return
        }
        val encryptedBody = File(outputFile.parentFile, "${outputFile.name}.body")
        sourceFile.aesEncryptTo(encryptedBody, BuildConfig.ENC_KEY, JSExtensionCryLoader.CHUNK_SIZE)
        if (!encryptedBody.isFile || encryptedBody.length() <= 0) throw IOException("插件加密失败")
        outputFile.outputStream().buffered().use { output ->
            output.write(JSExtensionCryLoader.FIRST_LINE_MARK)
            encryptedBody.inputStream().use { it.copyTo(output) }
        }
        encryptedBody.delete()
        if (outputFile.length() <= JSExtensionCryLoader.FIRST_LINE_MARK.size) {
            throw IOException("插件加密失败")
        }
    }

    fun validateIcon(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) throw IOException("图标大小必须在 1 B 到 4 MiB 之间")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096) {
            throw IOException("图标不是有效图片或尺寸超过 4096 px")
        }
    }

    fun defaultIcon(label: String): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(44, 98, 75))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 120f
        }
        val text = label.trim().take(1).ifEmpty { "JS" }
        val baseline = 128f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, 128f, baseline, paint)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Int) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("插件包条目超过大小限制")
            output.write(buffer, 0, read)
        }
    }
}
