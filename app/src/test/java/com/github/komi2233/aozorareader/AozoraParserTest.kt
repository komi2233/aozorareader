package com.github.komi2233.aozorareader

import com.github.komi2233.aozorareader.data.AozoraParser
import com.github.komi2233.aozorareader.ui.PageSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ルビのパース処理、およびページ分割アルゴリズムの動作検証を行うユニットテスト。
 */
class AozoraParserTest {

    @Test
    fun testParseExplicitRuby() {
        val rawText = """
            タイトル
            著者
            ｜青空《あおぞら》
        """.trimIndent()
        
        val book = AozoraParser.parse(rawText)
        
        assertEquals("タイトル", book.title)
        assertEquals("著者", book.author)
        
        // 本文段落
        val bodyParagraph = book.paragraphs.first { it.chars.isNotEmpty() }
        assertEquals(2, bodyParagraph.chars.size)
        
        // 親文字の先頭 '青'
        val firstChar = bodyParagraph.chars[0]
        assertEquals('青', firstChar.char)
        assertEquals("あおぞら", firstChar.ruby)
        assertEquals(2, firstChar.rubyLength)
        
        // 親文字の2文字目 '空'
        val secondChar = bodyParagraph.chars[1]
        assertEquals('空', secondChar.char)
        assertNull(secondChar.ruby)
        assertEquals(0, secondChar.rubyLength)
    }

    @Test
    fun testParseImplicitRuby() {
        val rawText = """
            タイトル
            著者
            漢字《かんじ》
        """.trimIndent()
        
        val book = AozoraParser.parse(rawText)
        val bodyParagraph = book.paragraphs.first { it.chars.isNotEmpty() }
        assertEquals(2, bodyParagraph.chars.size)
        
        val firstChar = bodyParagraph.chars[0]
        assertEquals('漢', firstChar.char)
        assertEquals("かんじ", firstChar.ruby)
        assertEquals(2, firstChar.rubyLength)
        
        val secondChar = bodyParagraph.chars[1]
        assertEquals('字', secondChar.char)
        assertNull(secondChar.ruby)
    }

    @Test
    fun testPageSplitter() {
        val rawText = """
            テスト本
            著者
            青空文庫リーダーを縦書きで作成テスト。これは禁則の確認です。
        """.trimIndent()
        
        val book = AozoraParser.parse(rawText)
        
        // 横幅300, 高さ300、フォントサイズ30、余白・行間・字間なし
        // 1行の最大文字数 = 300 / 30 = 10文字
        // 1ページの最大行数 = 300 / 30 = 10行
        val pages = PageSplitter.split(
            book = book,
            width = 300f,
            height = 300f,
            fontSize = 30f,
            lineSpacing = 0f,
            charSpacing = 0f,
            paddingLeft = 0f,
            paddingRight = 0f,
            paddingTop = 0f,
            paddingBottom = 0f
        )
        
        assertNotNull(pages)
        // 全体の本文（27文字）が10文字ずつ分割される
        // 1行目: "青空文庫リーダーを" (10文字)
        // 2行目: "縦書きで作成テスト" (9文字。次の「。」は行頭禁則対象なので2行目に収めるか、3行目へ。
        //        PageSplitterでは「。」を行頭にしないよう、2行目を1文字減らして3行目へ送るか、1文字増やしてぶら下げる。
        //        ここでは最大幅300で増やせないので、1文字減らして「テス」で切り、「ト。」から次の行に送るように動く)
        // いずれにせよ、文字数からして1ページ（10行以内）に十分収まるはず。
        assertEquals(1, pages.size)
        
        val firstPage = pages[0]
        // 複数行に分割されていることを確認
        assertEquals(true, firstPage.ranges.size >= 3)
        
        // 最初の行が0文字目から始まっていること
        assertEquals(0, firstPage.ranges[0].startCharIndex)
    }

    @Test
    fun testParseCsvLine() {
        // ダブルクォーテーションで囲まれたカンマを含むフィールドが正しく分割されることをテスト
        val line = "001257,\"アーヴィング ワシントン\",059898,\"ウェストミンスター,寺院\",新字新仮名"
        val tokens = AozoraParser.parseCsvLine(line)
        
        assertEquals(5, tokens.size)
        assertEquals("001257", tokens[0])
        assertEquals("アーヴィング ワシントン", tokens[1])
        assertEquals("059898", tokens[2])
        assertEquals("ウェストミンスター,寺院", tokens[3])
        assertEquals("新字新仮名", tokens[4])
    }

    @Test
    fun testParseCsv() {
        val csvText = """
            人物ID,著者名,作品ID,作品名,仮名遣い種別,翻訳者名等,入力者名,校正者名,状態,状態の開始日
            000074,梶井 基次郎,046349,檸檬,新字新仮名,,えにしだ,砂場清隆,公開,2020-04-03
            000074,梶井 基次郎,046350,城のある町にて,新字新仮名,,えにしだ,砂場清隆,予告,2020-04-03
        """.trimIndent()
        
        val list = AozoraParser.parseCsv(csvText)
        
        // 「公開」状態の「檸檬」のみリストに保持され、「予告」状態の「城のある町にて」は除外されることをテスト
        assertEquals(1, list.size)
        
        val book = list[0]
        assertEquals("000074", book.personId)
        assertEquals("梶井 基次郎", book.author)
        assertEquals("046349", book.bookId)
        assertEquals("檸檬", book.title)
    }
}
