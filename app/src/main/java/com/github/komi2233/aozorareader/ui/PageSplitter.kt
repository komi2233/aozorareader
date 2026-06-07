package com.github.komi2233.aozorareader.ui

import com.github.komi2233.aozorareader.data.AozoraChar
import com.github.komi2233.aozorareader.data.Book

/**
 * ページに描画する段落とその段落内の文字範囲を表すデータクラス。
 */
data class PageRange(
    val paragraphIndex: Int,
    /** 開始インデックス（含む） */
    val startCharIndex: Int,
    /** 終了インデックス（含まない） */
    val endCharIndex: Int
)

/**
 * 1ページ分の描画情報。
 */
data class BookPage(
    val ranges: List<PageRange>
)

/**
 * テキスト全体を画面サイズに基づいてページに分割するレイアウトエンジン。
 */
object PageSplitter {

    /**
     * 指定された表示パラメータのもとで、小説データをページリストに分割します。
     */
    fun split(
        book: Book,
        width: Float,
        height: Float,
        fontSize: Float,
        lineSpacing: Float,
        charSpacing: Float,
        paddingLeft: Float,
        paddingRight: Float,
        paddingTop: Float,
        paddingBottom: Float
    ): List<BookPage> {
        val pages = mutableListOf<BookPage>()
        
        // 描画可能領域の計算
        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = height - paddingTop - paddingBottom
        
        if (usableWidth <= 0 || usableHeight <= 0 || fontSize <= 0) {
            return emptyList()
        }

        // 縦書き（右から左へ行が進む）：
        // 1. 1行の高さ（Y方向）に収まる最大文字数
        val maxCharsPerLine = (usableHeight / (fontSize + charSpacing)).toInt().coerceAtLeast(1)
        // 2. 1ページ幅（X方向）に収まる最大行数
        val maxLinesPerPage = (usableWidth / (fontSize + lineSpacing)).toInt().coerceAtLeast(1)

        var currentRanges = mutableListOf<PageRange>()
        var currentLinesInPage = 0
        
        var paragraphIdx = 0
        while (paragraphIdx < book.paragraphs.size) {
            val paragraph = book.paragraphs[paragraphIdx]
            val totalChars = paragraph.chars.size
            
            // 空行（段落間の改行）のハンドリング
            if (totalChars == 0) {
                if (currentLinesInPage >= maxLinesPerPage) {
                    pages.add(BookPage(currentRanges))
                    currentRanges = mutableListOf()
                    currentLinesInPage = 0
                }
                currentRanges.add(PageRange(paragraphIdx, 0, 0))
                currentLinesInPage++
                paragraphIdx++
                continue
            }
            
            var charIdx = 0
            while (charIdx < totalChars) {
                // 1ページの行数制限に達したら改ページ
                if (currentLinesInPage >= maxLinesPerPage) {
                    pages.add(BookPage(currentRanges))
                    currentRanges = mutableListOf()
                    currentLinesInPage = 0
                }
                
                val charsLeft = totalChars - charIdx
                var lineLength = charsLeft.coerceAtMost(maxCharsPerLine)
                
                // 簡易的な行頭禁則処理（「。」や「、」などの閉じ記号が行頭に来ないようにする）
                if (charIdx + lineLength < totalChars) {
                    val nextChar = paragraph.chars[charIdx + lineLength].char
                    if (isLineHeadKinsoku(nextChar)) {
                        // 行末の空きがある場合は1文字ぶら下げる、ない場合は1文字減らして次の行に送る
                        if (lineLength < maxCharsPerLine) {
                            lineLength++
                        } else {
                            lineLength--
                        }
                    }
                }
                
                // 安全対策として、最低でも1文字は進める
                lineLength = lineLength.coerceAtLeast(1)
                
                currentRanges.add(PageRange(paragraphIdx, charIdx, charIdx + lineLength))
                currentLinesInPage++
                charIdx += lineLength
            }
            paragraphIdx++
        }
        
        // 最後のページの処理
        if (currentRanges.isNotEmpty()) {
            pages.add(BookPage(currentRanges))
        }
        
        return pages
    }

    /**
     * 行頭禁則文字かどうかを判定。
     */
    private fun isLineHeadKinsoku(c: Char): Boolean {
        return c == '、' || c == '。' || c == '」' || c == '』' || c == '）' || c == '］' ||
               c == '｝' || c == '〉' || c == '》' || c == '？' || c == '！' || c == '々' || 
               c == 'ー' || c == '・'
    }
}
