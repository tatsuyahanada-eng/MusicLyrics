package com.netdiag.core

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the memo, the diagnostic log and the device photos into a single
 * A4 PDF using the framework [PdfDocument] (no external libraries).
 */
object PdfExporter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 32f

    fun export(context: Context, memo: String, logText: String, imagePaths: List<String>): File {
        val doc = PdfDocument()
        val body = Paint().apply { color = Color.BLACK; textSize = 10.5f }
        val heading = Paint().apply { color = Color.rgb(11, 107, 168); textSize = 14f; isFakeBoldText = true }
        val title = Paint().apply { color = Color.BLACK; textSize = 18f; isFakeBoldText = true }
        val lineH = 15f
        val maxW = PAGE_W - MARGIN * 2

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun ensure(needed: Float) {
            if (y + needed > PAGE_H - MARGIN) newPage()
        }

        fun drawHeading(text: String) {
            ensure(lineH * 2)
            y += lineH
            canvas.drawText(text, MARGIN, y, heading)
            y += 6f
        }

        fun drawParagraph(text: String, paint: Paint) {
            val source = text.ifBlank { "（なし）" }
            for (raw in source.split("\n")) {
                if (raw.isEmpty()) { ensure(lineH); y += lineH; continue }
                var start = 0
                while (start < raw.length) {
                    val count = paint.breakText(raw, start, raw.length, true, maxW, null)
                        .coerceAtLeast(1)
                    val end = (start + count).coerceAtMost(raw.length)
                    ensure(lineH)
                    y += lineH
                    canvas.drawText(raw.substring(start, end), MARGIN, y, paint)
                    start = end
                }
            }
        }

        // Title
        y += 18f
        canvas.drawText("NetScope レポート", MARGIN, y, title)
        y += lineH
        canvas.drawText(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            MARGIN, y, body,
        )

        drawHeading("メモ")
        drawParagraph(memo, body)

        drawHeading("診断ログ")
        drawParagraph(logText, body)

        drawHeading("機器画像 (${imagePaths.size})")
        val maxImgH = PAGE_H - MARGIN * 2 - lineH
        for (path in imagePaths) {
            val bmp = decodeScaled(path, maxW.toInt(), maxImgH.toInt()) ?: continue
            ensure(bmp.height + 10f)
            y += 6f
            canvas.drawBitmap(bmp, MARGIN, y, null)
            y += bmp.height + 6f
        }

        doc.finishPage(page)

        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val out = File(dir, "netscope_${System.currentTimeMillis()}.pdf")
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun decodeScaled(path: String, maxW: Int, maxH: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxW * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        var w = maxW
        var h = (bmp.height * (w.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
        if (h > maxH) {
            h = maxH
            w = (bmp.width * (h.toFloat() / bmp.height)).toInt().coerceAtLeast(1)
        }
        return android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
