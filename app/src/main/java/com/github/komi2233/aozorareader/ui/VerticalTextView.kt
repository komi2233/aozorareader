package com.github.komi2233.aozorareader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.github.komi2233.aozorareader.data.Book

/**
 * WebViewを使わずに、縦書き表示とルビ表示を行うためのカスタムView。
 */
class VerticalTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
    }

    private val rubyPaint = Paint().apply {
        isAntiAlias = true
        color = Color.DKGRAY
    }

    private var book: Book? = null
    private var page: BookPage? = null

    var fontSize: Float = 42f
        set(value) {
            field = value
            textPaint.textSize = value
            rubyPaint.textSize = value * 0.45f
            invalidate()
        }

    var lineSpacing: Float = 28f
        set(value) {
            field = value
            invalidate()
        }

    var charSpacing: Float = 4f
        set(value) {
            field = value
            invalidate()
        }

    init {
        textPaint.textSize = fontSize
        rubyPaint.textSize = fontSize * 0.45f
        updatePaintColors()
    }

    private fun updatePaintColors() {
        val attrs = intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary)
        val ta = context.obtainStyledAttributes(attrs)
        val primary = ta.getColor(0, Color.BLACK)
        val secondary = ta.getColor(1, Color.DKGRAY)
        ta.recycle()

        textPaint.color = if (primary != 0) primary else Color.BLACK
        rubyPaint.color = if (secondary != 0) secondary else Color.DKGRAY
    }

    fun setCustomTypeface(typeface: Typeface?) {
        textPaint.typeface = typeface
        rubyPaint.typeface = typeface
        invalidate()
    }

    fun setPageData(book: Book, page: BookPage) {
        this.book = book
        this.page = page
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentBook = book ?: return
        val currentPage = page ?: return

        val pRight = paddingRight.toFloat()
        val pTop = paddingTop.toFloat()
        
        var lineIndex = 0

        for (range in currentPage.ranges) {
            val paragraph = currentBook.paragraphs.getOrNull(range.paragraphIndex) ?: continue
            
            if (range.startCharIndex == 0 && range.endCharIndex == 0) {
                lineIndex++
                continue
            }
            
            val startX = width - pRight - (lineIndex * (fontSize + lineSpacing)) - fontSize
            var currentY = pTop
            
            for (charIdx in range.startCharIndex until range.endCharIndex) {
                val aozoraChar = paragraph.chars.getOrNull(charIdx) ?: continue
                val drawChar = convertToVerticalGlyph(aozoraChar.char)
                val shouldRotate = isRotatable(aozoraChar.char)

                if (shouldRotate) {
                    canvas.save()
                    val centerX = startX + fontSize / 2f
                    val centerY = currentY + fontSize / 2f
                    canvas.rotate(90f, centerX, centerY)
                    val textWidth = textPaint.measureText(drawChar)
                    canvas.drawText(drawChar, centerX - textWidth / 2f, centerY + fontSize * 0.35f, textPaint)
                    canvas.restore()
                } else {
                    canvas.drawText(drawChar, startX, currentY + fontSize * 0.82f, textPaint)
                }
                
                if (aozoraChar.ruby != null && aozoraChar.rubyLength > 0) {
                    drawRuby(
                        canvas = canvas,
                        rubyText = aozoraChar.ruby,
                        rubyLength = aozoraChar.rubyLength,
                        rubyX = startX + fontSize + 6f,
                        parentStartY = currentY
                    )
                }
                currentY += fontSize + charSpacing
            }
            lineIndex++
        }
    }

    private fun drawRuby(
        canvas: Canvas,
        rubyText: String,
        rubyLength: Int,
        rubyX: Float,
        parentStartY: Float
    ) {
        val parentTotalHeight = rubyLength * fontSize + (rubyLength - 1) * charSpacing
        val rubyCharCount = rubyText.length
        if (rubyCharCount == 0) return

        val rubyFontSize = rubyPaint.textSize
        
        if (rubyCharCount == 1) {
            val rubyY = parentStartY + (parentTotalHeight / 2) - (rubyFontSize / 2)
            canvas.drawText(rubyText, rubyX, rubyY + rubyFontSize * 0.82f, rubyPaint)
            return
        }

        val rubySpacing = (parentTotalHeight - (rubyCharCount * rubyFontSize)) / (rubyCharCount + 1)
        var currentRubyY = parentStartY + rubySpacing
        
        for (i in 0 until rubyCharCount) {
            canvas.drawText(rubyText[i].toString(), rubyX, currentRubyY + rubyFontSize * 0.82f, rubyPaint)
            currentRubyY += rubyFontSize + rubySpacing
        }
    }

    private fun isRotatable(char: Char): Boolean {
        return char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || 
               char in 'ａ'..'ｚ' || char in 'Ａ'..'Ｚ' || char in '０'..'９'
    }

    private fun convertToVerticalGlyph(char: Char): String {
        return when (char) {
            '、' -> "\uFE11"
            '。' -> "\uFE12"
            'ー' -> "\uFE31"
            '―' -> "\uFE31"
            '…' -> "\uFE19"
            '（' -> "︵"
            '）' -> "︶"
            '「' -> "﹁"
            '」' -> "﹂"
            '『' -> "﹃"
            '』' -> "﹄"
            '【' -> "︻"
            '】' -> "︼"
            '［' -> "﹇"
            '］' -> "﹈"
            '｛' -> "︷"
            '｝' -> "︸"
            else -> char.toString()
        }
    }
}
