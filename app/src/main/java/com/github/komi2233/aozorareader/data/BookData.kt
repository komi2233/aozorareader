package com.github.komi2233.aozorareader.data

/**
 * ルビが付いた、あるいは付いていない文字を表すデータクラス。
 * 縦書き描画エンジンが、この情報をもとに文字の隣にルビを配置します。
 */
data class AozoraChar(
    val char: Char,
    /**
     * ルビ文字列。
     * ルビがある場合はそのルビ、ない場合は null。
     * 複数文字にまたがるルビの場合、先頭の文字にルビ文字列全体を保持し、
     * 続く親文字には「ルビの継続」を示す特殊なマーカー（空文字など）を設定するか、
     * あるいは各文字に分割して配置するなどの設計がありますが、
     * ここではシンプルに、ルビグループの最初の文字にルビテキストを持たせます。
     */
    val ruby: String? = null,
    /**
     * ルビが何文字の親文字にかかっているかを表す長さ（グループルビの描画に利用）
     */
    val rubyLength: Int = 0
)

/**
 * 1つの段落を表すデータクラス。
 * 小説の改行で区切られたひとまとまりの文章。
 */
data class Paragraph(
    val chars: List<AozoraChar>
)

/**
 * 解析済みの本全体を表すデータクラス。
 */
data class Book(
    val title: String,
    val author: String,
    val paragraphs: List<Paragraph>
)

/**
 * 作品リスト内の各書籍情報を保持するデータクラス。
 */
data class BookListItem(
    val personId: String,
    val author: String,
    val bookId: String,
    val title: String
)
