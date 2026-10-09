package com.example.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Files ko phone par hi padhta hai (koi library/server nahi):
 *  docx / pptx / xlsx -> zip ke andar ka XML se text. PDF -> pehle kuch pages ki image (PdfRenderer).
 * PDF ka text nikalna library ke bina possible nahi, isliye PDF pages image banakar vision model ko jate hain.
 */
object Docs {
    class Result(val text: String?, val images: List<String>, val note: String?)

    private const val MAX_CHARS = 20000

    fun extract(ctx: Context, uri: Uri, name: String, mime: String): Result {
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when {
                ext == "docx" -> Result(docx(ctx, uri), emptyList(), null)
                ext == "pptx" -> Result(pptx(ctx, uri), emptyList(), null)
                ext == "xlsx" -> Result(xlsx(ctx, uri), emptyList(), null)
                ext == "pdf" || mime == "application/pdf" -> pdf(ctx, uri)
                ext == "doc" || ext == "xls" || ext == "ppt" ->
                    Result(null, emptyList(), "Purana .$ext format local me nahi padha jata. docx/xlsx/pptx ya PDF me save karke bhejo.")
                else -> Result(null, emptyList(), "Ye file type local me nahi padha ja sakta (sirf image, text/code, docx, xlsx, pptx, pdf).")
            }
        } catch (e: Exception) {
            Result(null, emptyList(), "File padh nahi payi: ${e.message}")
        }
    }

    private fun readZip(ctx: Context, uri: Uri, want: (String) -> Boolean): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || !want(e.name)) continue
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        if (total < 3_000_000) bos.write(buf, 0, n)
                        total += n
                    }
                    out[e.name] = bos.toString("UTF-8")
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun stripTags(s: String) = unescape(s.replace(Regex("<[^>]+>"), ""))

    private fun docx(ctx: Context, uri: Uri): String {
        val m = readZip(ctx, uri) { it == "word/document.xml" }
        val xml = m["word/document.xml"] ?: return ""
        val x = xml.replace("</w:p>", "\n").replace("<w:tab/>", "\t").replace("<w:br/>", "\n")
        return stripTags(x).replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_CHARS)
    }

    private fun pptx(ctx: Context, uri: Uri): String {
        val slideRe = Regex("ppt/slides/slide(\\d+)\\.xml")
        val m = readZip(ctx, uri) { slideRe.matches(it) }
        val sb = StringBuilder()
        for (k in m.keys.sortedBy { slideRe.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }) {
            sb.append("--- Slide ${slideRe.matchEntire(k)?.groupValues?.get(1)} ---\n")
            sb.append(stripTags((m[k] ?: "").replace("</a:p>", "\n")).replace(Regex("\n{2,}"), "\n").trim()).append("\n\n")
            if (sb.length > MAX_CHARS) break
        }
        return sb.toString().trim().take(MAX_CHARS)
    }

    private fun xlsx(ctx: Context, uri: Uri): String {
        val sheetRe = Regex("xl/worksheets/sheet(\\d+)\\.xml")
        val m = readZip(ctx, uri) { it == "xl/sharedStrings.xml" || sheetRe.matches(it) }
        val shared = ArrayList<String>()
        val opt = setOf(RegexOption.DOT_MATCHES_ALL)
        m["xl/sharedStrings.xml"]?.let { xml ->
            for (si in Regex("<si>(.*?)</si>", opt).findAll(xml)) {
                shared.add(Regex("<t[^>]*>(.*?)</t>", opt).findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) })
            }
        }
        val sb = StringBuilder()
        val sheets = m.keys.filter { sheetRe.matches(it) }.sortedBy { sheetRe.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        for (k in sheets.take(5)) {
            sb.append("--- Sheet ${sheetRe.matchEntire(k)?.groupValues?.get(1)} ---\n")
            for (row in Regex("<row[^>]*>(.*?)</row>", opt).findAll(m[k] ?: "")) {
                val cells = ArrayList<String>()
                for (c in Regex("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", opt).findAll(row.groupValues[1])) {
                    val attrs = c.groupValues[1]
                    val inner = c.groupValues[2]
                    val type = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                    val v = Regex("<v>(.*?)</v>", opt).find(inner)?.groupValues?.get(1) ?: ""
                    cells.add(
                        when (type) {
                            "s" -> shared.getOrNull(v.toIntOrNull() ?: -1) ?: ""
                            "inlineStr" -> stripTags(Regex("<t[^>]*>(.*?)</t>", opt).find(inner)?.groupValues?.get(1) ?: "")
                            else -> unescape(v)
                        }
                    )
                }
                sb.append(cells.joinToString("\t").trimEnd()).append('\n')
                if (sb.length > MAX_CHARS) break
            }
            if (sb.length > MAX_CHARS) break
        }
        return sb.toString().trim().take(MAX_CHARS)
    }

    private fun pdf(ctx: Context, uri: Uri): Result {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: return Result(null, emptyList(), "PDF khul nahi payi")
        val paths = ArrayList<String>()
        var note: String? = null
        pfd.use {
            val r = PdfRenderer(it)
            try {
                val n = minOf(r.pageCount, 4)
                for (i in 0 until n) {
                    val page = r.openPage(i)
                    val w = 1000
                    val h = (1000f * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    val f = File(ChatStore.mediaDir(ctx), "pdf_${System.currentTimeMillis()}_$i.jpg")
                    FileOutputStream(f).use { os -> bmp.compress(Bitmap.CompressFormat.JPEG, 80, os) }
                    paths.add(f.absolutePath)
                }
                note = "PDF ke pehle $n page (kul ${r.pageCount}) image bana kar jode. Text nahi nikala, isliye samajhne ke liye vision model chahiye."
            } finally {
                r.close()
            }
        }
        return Result(null, paths, note)
    }
}
