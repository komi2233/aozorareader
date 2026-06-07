package com.github.komi2233.aozorareader.data

import java.util.regex.Pattern

/**
 * 青空文庫形式のテキストファイルをパースして、タイトル、著者、
 * およびルビ情報が付加された段落リスト（Book型）に変換するパーサー。
 */
object AozoraParser {

    private val NOTE_PATTERN = Pattern.compile("［＃[^］\n]+］")

    /**
     * 青空文庫テキストの全体を解析します。
     */
    fun parse(rawText: String): Book {
        val lines = rawText.split(Pattern.compile("\\r?\\n"))
        
        var title = ""
        var author = ""
        
        val paragraphs = mutableListOf<Paragraph>()
        
        for (i in lines.indices) {
            val rawLine = lines[i].trim()
            if (rawLine.isEmpty()) {
                // タイトルと著者名が設定された後であれば、空行も段落として保持する
                if (title.isNotEmpty() && author.isNotEmpty()) {
                    paragraphs.add(Paragraph(emptyList()))
                }
                continue
            }
            
            // 最初の非空行をタイトルと著者名に設定
            if (title.isEmpty()) {
                title = rawLine
                continue
            }
            if (author.isEmpty()) {
                author = rawLine
                continue
            }
            
            // 区切り境界線「-------------------------------------------------------」の行は
            // 縦書き描画時に非常に長い横線となって表示が崩れるため、スキップ（空行に置換）します
            if (rawLine.startsWith("-------------------------------------------------------") ||
                rawLine.startsWith("---------")) {
                paragraphs.add(Paragraph(emptyList()))
                continue
            }
            
            // 注記のクリーンアップ（例: ［＃「それ」に傍点］などの注記記法を取り除く）
            val cleanLine = NOTE_PATTERN.matcher(rawLine).replaceAll("")
            if (cleanLine.isEmpty()) continue
            
            // 段落の始まりを一字下げする（行頭が「「」や「『」などの開始記号、または既にスペースで始まっている場合を除く）
            val finalLine = if (shouldIndent(cleanLine)) {
                "　$cleanLine"
            } else {
                cleanLine
            }
            
            val paragraph = parseLine(finalLine)
            paragraphs.add(paragraph)
        }
        
        return Book(title, author, paragraphs)
    }

    /**
     * 段落を一字下げすべきか判定します。
     */
    private fun shouldIndent(line: String): Boolean {
        if (line.isEmpty()) return false
        val firstChar = line[0]
        // 既にスペースで始まっている場合は不要
        if (firstChar == '　' || firstChar == ' ' || firstChar == '\t') return false
        // 開始括弧類で始まっている場合は、一般的に一字下げしない
        val openingBrackets = "「『（［｛〈《【"
        if (openingBrackets.contains(firstChar)) return false
        
        return true
    }

    /**
     * 1行をパースし、ルビ情報を付加した AozoraChar のリストに分解します。
     */
    private fun parseLine(line: String): Paragraph {
        val chars = mutableListOf<AozoraChar>()
        var index = 0
        
        while (index < line.length) {
            val char = line[index]
            
            // 1. ｜漢字《かんじ》のパース (明示的なルビ開始マーク)
            if (char == '｜') {
                val rubyStartIndex = line.indexOf('《', index)
                val rubyEndIndex = line.indexOf('》', index)
                if (rubyStartIndex in (index + 1)..<rubyEndIndex) {
                    val parentText = line.substring(index + 1, rubyStartIndex)
                    val rubyText = line.substring(rubyStartIndex + 1, rubyEndIndex)
                    
                    // 親文字のリストを作成。最初の文字にルビ文字列と親文字長を保持させる
                    for (j in parentText.indices) {
                        if (j == 0) {
                            chars.add(AozoraChar(parentText[j], rubyText, parentText.length))
                        } else {
                            chars.add(AozoraChar(parentText[j], null, 0))
                        }
                    }
                    index = rubyEndIndex + 1
                    continue
                }
            }
            
            // 2. 漢字《かんじ》のパース (｜がない暗黙的なルビ)
            if (char == '《') {
                val rubyEndIndex = line.indexOf('》', index)
                if (rubyEndIndex > index) {
                    val rubyText = line.substring(index + 1, rubyEndIndex)
                    
                    // 直前の漢字の連続部分を遡って特定する
                    var parentLength = 0
                    while (chars.size - 1 - parentLength >= 0) {
                        val prevChar = chars[chars.size - 1 - parentLength].char
                        if (isKanji(prevChar)) {
                            parentLength++
                        } else {
                            break
                        }
                    }
                    
                    if (parentLength > 0) {
                        // 遡った最初の親文字にルビ情報を上書きする
                        val firstParentIndex = chars.size - parentLength
                        val targetChar = chars[firstParentIndex].char
                        chars[firstParentIndex] = AozoraChar(targetChar, rubyText, parentLength)
                    }
                    index = rubyEndIndex + 1
                    continue
                }
            }
            
            // 3. 通常の文字
            chars.add(AozoraChar(char))
            index++
        }
        
        return Paragraph(chars)
    }

    /**
     * 文字が日本語の漢字（または繰り返しの記号）であるか判定します。
     */
    private fun isKanji(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FCB) || c == '々' || c == '〆' || c == 'ヶ' || c == 'ヵ'
    }

    /**
     * CSVテキスト全体を解析し、BookListItemのリストを返します。
     */
    fun parseCsv(csvText: String): List<BookListItem> {
        val list = mutableListOf<BookListItem>()
        val reader = java.io.BufferedReader(java.io.StringReader(csvText))
        var line = reader.readLine() // ヘッダーをスキップ

        line = reader.readLine()
        while (line != null) {
            val tokens = parseCsvLine(line)
            if (tokens.size >= 9) {
                val personId = tokens[0]
                val author = tokens[1]
                val bookId = tokens[2]
                val title = tokens[3]
                val status = tokens[8]
                
                // 公開状態の作品のみライブラリに含める
                if (status == "公開") {
                    list.add(BookListItem(personId, author, bookId, title))
                }
            }
            line = reader.readLine()
        }
        return list
    }

    /**
     * CSVの1行をダブルクォーテーションを考慮してカンマで分割します。
     */
    fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val cur = java.lang.StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                result.add(cur.toString().trim())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        result.add(cur.toString().trim())
        return result
    }
}
