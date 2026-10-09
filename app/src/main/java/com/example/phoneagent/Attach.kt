package com.example.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * File/image ko phone par hi padhta hai. Text, DOCX, XLSX (text cells), PPTX local nikalte hain.
 * Image aur PDF ko model ko bhejna padta hai (vision model, online) — uski permission chat me poochi jati hai.
 */
object Attach {

    /** kind: text | image | pdf | unsupported */
    class Picked(
        val name: String,
        val kind: String,
        val text: String?,
        val attachment: Attachment?,
        val note: String
    )

    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "log", "kt", "java", "py", "js", "html", "htm",
        "yaml", "yml", "ini", "cfg", "sql", "c", "cpp", "h", "sh", "gradle"
    )
    private val OFFICE_EXT = setOf("docx", "xlsx", "pptx")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    fun load(c: Context, uri: Uri): Picked {
        val name = displayName(c, uri)
        val mime = c.contentResolver.getType(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when {
                mime.startsWith("image/") || ext in IMAGE_EXT -> loadImage(c, uri, name)
                mime == "application/pdf" || ext == "pdf" -> loadPdf(c, uri, name)
                ext in OFFICE_EXT -> {
                    val t = officeText(c, uri, ext)
                    if (t.isBlank()) {
                        Picked(name, "unsupported", null, null, "File me padhne laayak text nahi mila")
                    } else {
                        Picked(name, "text", t.take(80_000), null, "Local me text nikala gaya")
                    }
                }
                ext in TEXT_EXT || mime.startsWith("text/") -> {
                    Picked(name, "text", readText(c, uri), null, "Local me padha gaya")
                }
                else -> Picked(
                    name, "unsupported", null, null,
                    "Ye file type abhi supported nahi (TXT/MD/CSV/JSON, DOCX, XLSX, PPTX, image, PDF chalte hain)"
                )
            }
        } catch (e: Exception) {
            Picked(name, "unsupported", null, null, "File padh nahi payi: ${e.message}")
        }
    }

    private fun readText(c: Context, uri: Uri): String {
        val ins = c.contentResolver.openInputStream(uri) ?: throw IllegalStateException("file khul nahi")
        ins.use {
            val buf = ByteArray(200_000)
            var n = 0
            while (n < buf.size) {
                val r = it.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return String(buf, 0, n, Charsets.UTF_8)
        }
    }

    /** Image ko chhota karke JPEG banata hai (bhejne ke liye). */
    private fun loadImage(c: Context, uri: Uri, name: String): Picked {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return Picked(name, "unsupported", null, null, "Image padh nahi payi")
        var sample = 1
        while (longest / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return Picked(name, "unsupported", null, null, "Image decode nahi hui")
        var quality = 85
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 15
        } while (bytes.size > 3_000_000 && quality > 30)
        bmp.recycle()
        return Picked(
            name, "image", null, Attachment(name, "image/jpeg", bytes),
            "Image ko samajhne ke liye vision model chahiye (online)"
        )
    }

    private fun loadPdf(c: Context, uri: Uri, name: String): Picked {
        val ins = c.contentResolver.openInputStream(uri) ?: throw IllegalStateException("file khul nahi")
        val out = ByteArrayOutputStream()
        ins.use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = it.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > 10_000_000) {
                    return Picked(name, "unsupported", null, null, "PDF 10 MB se badi hai")
                }
            }
        }
        return Picked(
            name, "pdf", null, Attachment(name, "application/pdf", out.toByteArray()),
            "PDF ka text local nahi nikal sakte; padhne ke liye online model (Gemini) chahiye"
        )
    }

    private fun officeText(c: Context, uri: Uri, ext: String): String {
        val sb = StringBuilder()
        val ins = c.contentResolver.openInputStream(uri) ?: return ""
        ZipInputStream(ins).use { zis ->
            var e = zis.nextEntry
            while (e != null && sb.length < 120_000) {
                val n = e.name
                val want = when (ext) {
                    "docx" -> n == "word/document.xml"
                    "pptx" -> n.startsWith("ppt/slides/slide") && n.endsWith(".xml")
                    "xlsx" -> n == "xl/sharedStrings.xml"
                    else -> false
                }
                if (want) {
                    if (ext == "pptx") sb.append("\n--- ").append(n.substringAfterLast('/')).append(" ---\n")
                    extractXml(zis, ext, sb)
                }
                e = zis.nextEntry
            }
        }
        return sb.toString().trim()
    }

    private fun extractXml(zis: InputStream, ext: String, sb: StringBuilder) {
        val p = Xml.newPullParser()
        p.setInput(zis, "UTF-8")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && sb.length < 120_000) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name.substringAfter(':') == "t") sb.append(p.nextText())
            } else if (ev == XmlPullParser.END_TAG) {
                val ln = p.name.substringAfter(':')
                if ((ext != "xlsx" && ln == "p") || (ext == "xlsx" && ln == "si")) sb.append('\n')
            }
            ev = p.next()
        }
    }
}
