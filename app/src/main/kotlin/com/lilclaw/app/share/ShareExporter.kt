package com.lilclaw.app.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.lilclaw.app.data.ChatMessage

/**
 * Generates shareable card images from conversation snippets.
 */
object ShareExporter {

    data class ShareCard(
        val messages: List<ChatMessage>,
        val theme: CardTheme = CardTheme.LIGHT,
    )

    enum class CardTheme(
        val bgColor: Int,
        val textColor: Int,
        val bubbleBgUser: Int,
        val bubbleBgAssistant: Int,
        val accentColor: Int,
    ) {
        LIGHT(
            bgColor = Color.parseColor("#FFF9F5"),
            textColor = Color.parseColor("#1C1B1A"),
            bubbleBgUser = Color.parseColor("#E8853D"),
            bubbleBgAssistant = Color.parseColor("#F2E8DE"),
            accentColor = Color.parseColor("#E8853D"),
        ),
        DARK(
            bgColor = Color.parseColor("#1C1B1A"),
            textColor = Color.parseColor("#E7E2DD"),
            bubbleBgUser = Color.parseColor("#7F4100"),
            bubbleBgAssistant = Color.parseColor("#504539"),
            accentColor = Color.parseColor("#FFB77C"),
        ),
    }

    fun generateBitmap(context: Context, card: ShareCard): Bitmap {
        val width = 1080
        val padding = 48f
        val messageSpacing = 24f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = card.theme.textColor
            textSize = 40f
            typeface = Typeface.DEFAULT
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = card.theme.accentColor
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }

        // Calculate height
        var totalHeight = padding * 2 + 120 // top + bottom (footer)
        for (msg in card.messages) {
            totalHeight += 60 + estimateTextHeight(msg.content, textPaint, width - padding * 2 - 80) + messageSpacing
        }

        val bitmap = Bitmap.createBitmap(width, totalHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(card.theme.bgColor)

        var y = padding

        for (msg in card.messages) {
            val label = if (msg.role == "user") "You" else "LilClaw"
            canvas.drawText(label, padding, y + 40, labelPaint)
            y += 52

            val lines = wrapText(msg.content, textPaint, width - padding * 2 - 40)
            for (line in lines) {
                canvas.drawText(line, padding + 20, y + 40, textPaint)
                y += 48
            }
            y += messageSpacing
        }

        // Footer
        y = totalHeight - 80
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = card.theme.accentColor
            textSize = 28f
            typeface = Typeface.DEFAULT
        }
        canvas.drawText("✦ LilClaw · Pocket AI Gateway", padding, y, footerPaint)

        return bitmap
    }

    private fun estimateTextHeight(text: String, paint: Paint, maxWidth: Float): Float {
        val lines = wrapText(text, paint, maxWidth)
        return lines.size * 48f
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            val words = paragraph.split(" ")
            var line = ""
            for (word in words) {
                val test = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(test) <= maxWidth) {
                    line = test
                } else {
                    if (line.isNotEmpty()) result.add(line)
                    line = word
                }
            }
            if (line.isNotEmpty()) result.add(line)
        }
        return result
    }
}
