package com.meetnote.recorder.export

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream

/**
 * 把 PPT 图按顺序排成 A4 PDF。图片解码失败的页会跳过，但页码照常前进，
 * 这样 PDF 页序与放映顺序始终一致。
 */
class PdfExporter {

    /** @return 实际写入的 PDF 页数 */
    fun export(
        files: List<File>,
        out: OutputStream,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int {
        require(files.isNotEmpty()) { "没有可导出的页面" }
        val document = PdfDocument()
        var written = 0
        try {
            files.forEachIndexed { i, file ->
                val pageNumber = i + 1
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PdfLayout.PAGE_WIDTH.toInt(),
                    PdfLayout.PAGE_HEIGHT.toInt(),
                    pageNumber,
                ).create()

                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(android.graphics.Color.WHITE)

                val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (bitmap != null) {
                    val box = PdfLayout.fit(bitmap.width, bitmap.height)
                    val dst = android.graphics.Rect(
                        box.left.toInt(), box.top.toInt(),
                        (box.left + box.width).toInt(), (box.top + box.height).toInt(),
                    )
                    canvas.drawBitmap(bitmap, null, dst, null)
                    bitmap.recycle()
                }

                document.finishPage(page)
                written++
                onProgress?.invoke(written, files.size)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return written
    }
}
