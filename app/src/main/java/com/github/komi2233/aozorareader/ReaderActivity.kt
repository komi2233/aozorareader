package com.github.komi2233.aozorareader

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.komi2233.aozorareader.data.AozoraDownloader
import com.github.komi2233.aozorareader.data.AozoraParser
import com.github.komi2233.aozorareader.data.Book
import com.github.komi2233.aozorareader.data.db.AppDatabase
import com.github.komi2233.aozorareader.data.db.BookEntity
import com.github.komi2233.aozorareader.ui.BookPage
import com.github.komi2233.aozorareader.ui.PageSplitter
import com.github.komi2233.aozorareader.ui.VerticalTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 送信されたZIPのダウンロードURLをもとに書籍データを取得し、
 * WebViewを使用せずに縦書きで表示、ページスワイプを行う読書画面。
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ZIP_URL = "extra_zip_url"
        const val EXTRA_PERSON_ID = "extra_person_id"
        const val EXTRA_BOOK_ID = "extra_book_id"
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var pageNumberText: TextView
    private var seekBar: android.widget.SeekBar? = null
    private var bottomContainer: android.widget.LinearLayout? = null

    private val downloader = AozoraDownloader()
    private var book: Book? = null
    private var pages: List<BookPage> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = SettingsProvider(this)
        if (settings.isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        super.onCreate(savedInstanceState)

        val rootLayout = android.widget.FrameLayout(this).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            // 背景色をテーマから取得して設定
            val typedValue = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)) {
                if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                    typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    setBackgroundColor(typedValue.data)
                } else {
                    setBackgroundResource(typedValue.resourceId)
                }
            }
        }

        viewPager = ViewPager2(this).apply {
            id = View.generateViewId()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        progressBar = ProgressBar(this).apply {
            id = View.generateViewId()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            visibility = View.VISIBLE
        }

        statusText = TextView(this).apply {
            id = View.generateViewId()
            text = "小説を読み込み中..."
            textSize = 18f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
                topMargin = 160
            }
        }

        val bottomContainerView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
            // 背景は親ビューと同色（指定なし）にし、余白を調整
            setPadding(60, 5, 60, 30)
            visibility = View.GONE
        }
        bottomContainer = bottomContainerView

        val slider = android.widget.SeekBar(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 縦書き（右から左）なので、スライダーも右が先頭になるように反転させる
            rotation = 180f
        }
        seekBar = slider

        pageNumberText = TextView(this).apply {
            id = View.generateViewId()
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = 8
            }
        }

        bottomContainer?.addView(slider)
        bottomContainer?.addView(pageNumberText)

        rootLayout.addView(viewPager)
        rootLayout.addView(progressBar)
        rootLayout.addView(statusText)
        rootLayout.addView(bottomContainer)

        // スライダー操作時の処理
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewPager.setCurrentItem(progress, false)
                    updatePageNumber(progress, pages.size)
                }
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        // エッジタップ用の透明なビューを追加
        val leftTapView = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.2f).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.START
            }
            setOnClickListener {
                val current = viewPager.currentItem
                if (current < (viewPager.adapter?.itemCount ?: 0) - 1) {
                    viewPager.setCurrentItem(current + 1, true)
                }
            }
        }
        val rightTapView = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.2f).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.END
            }
            setOnClickListener {
                val current = viewPager.currentItem
                if (current > 0) {
                    viewPager.setCurrentItem(current - 1, true)
                }
            }
        }
        rootLayout.addView(leftTapView)
        rootLayout.addView(rightTapView)

        setContentView(rootLayout)

        val zipUrl = intent.getStringExtra(EXTRA_ZIP_URL)
        val personId = intent.getStringExtra(EXTRA_PERSON_ID)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)

        if (zipUrl.isNullOrEmpty() || personId == null || bookId == null) {
            Toast.makeText(this, "作品の指定が正しくありません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        rootLayout.post {
            var width = rootLayout.width.toFloat()
            var height = rootLayout.height.toFloat()
            if (width <= 0f || height <= 0f) {
                val dm = resources.displayMetrics
                width = dm.widthPixels.toFloat()
                height = dm.heightPixels.toFloat()
            }
            loadBook(zipUrl, personId, bookId, width, height)
        }
    }

    private fun loadBook(url: String, personId: String, bookId: String, width: Float, height: Float) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@ReaderActivity)
                val dao = db.bookDao()
                
                // 1. DBから既存データをチェック
                var entity = dao.getBook(personId, bookId)
                var rawText = entity?.rawText

                if (rawText == null) {
                    // 2. ダウンロード & 解凍
                    statusText.text = "小説をダウンロード中..."
                    val isEpubLink = url.endsWith(".epub", ignoreCase = true)
                    val tempFile = File(cacheDir, if (isEpubLink) "loaded_book.epub" else "loaded_book.zip")
                    rawText = withContext(Dispatchers.IO) {
                        downloader.downloadZip(url, tempFile)
                        downloader.unzipAndDecode(tempFile)
                    }
                    
                    // DB保存 (開封日時も更新)
                    if (entity != null) {
                        entity = entity.copy(isDownloaded = true, rawText = rawText, lastOpenedAt = System.currentTimeMillis())
                        dao.updateBook(entity)
                    }
                } else {
                    // 既にダウンロード済みの場合も、開封日時を更新する
                    if (entity != null) {
                        entity = entity.copy(lastOpenedAt = System.currentTimeMillis())
                        dao.updateBook(entity)
                    }
                }

                // 3. 本文の解析
                statusText.text = "小説を解析中..."
                val parsedBook = withContext(Dispatchers.Default) {
                    AozoraParser.parse(rawText!!)
                }
                book = parsedBook

                // 4. ページへの分割
                statusText.text = "ページを生成中..."
                val settings = SettingsProvider(this@ReaderActivity)
                val fontSizePx = settings.fontSize
                val lineSpacingPx = fontSizePx * 0.66f
                val charSpacingPx = fontSizePx * 0.11f
                val padLeft = 60f
                val padRight = 60f
                val padTop = 80f
                val padBottom = 140f // スライダーとの間隔を適度に調整

                val bookPages = withContext(Dispatchers.Default) {
                    PageSplitter.split(
                        book = parsedBook,
                        width = width,
                        height = height,
                        fontSize = fontSizePx,
                        lineSpacing = lineSpacingPx,
                        charSpacing = charSpacingPx,
                        paddingLeft = padLeft,
                        paddingRight = padRight,
                        paddingTop = padTop,
                        paddingBottom = padBottom
                    )
                }
                pages = bookPages
                Toast.makeText(this@ReaderActivity, "全 ${bookPages.size} ページを読み込みました", Toast.LENGTH_SHORT).show()

                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE
                
                bottomContainer?.visibility = View.VISIBLE
                seekBar?.max = (bookPages.size - 1).coerceAtLeast(0)

                viewPager.adapter = BookPagerAdapter(
                    book = parsedBook,
                    pages = bookPages,
                    fontSize = fontSizePx,
                    lineSpacing = lineSpacingPx,
                    charSpacing = charSpacingPx,
                    padLeft = padLeft,
                    padRight = padRight,
                    padTop = padTop,
                    padBottom = padBottom
                )

                // 5. 既読位置の復元
                val lastPos = entity?.lastReadPosition ?: 0
                if (lastPos in bookPages.indices) {
                    viewPager.setCurrentItem(lastPos, false)
                    seekBar?.progress = lastPos
                    updatePageNumber(lastPos, bookPages.size)
                } else {
                    seekBar?.progress = 0
                    updatePageNumber(0, bookPages.size)
                }

                viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        super.onPageSelected(position)
                        seekBar?.progress = position
                        updatePageNumber(position, bookPages.size)
                        
                        // 既読位置の保存
                        lifecycleScope.launch(Dispatchers.IO) {
                            val currentEntity = dao.getBook(personId, bookId)
                            if (currentEntity != null) {
                                dao.updateBook(currentEntity.copy(lastReadPosition = position))
                            }
                        }
                    }
                })
                
            } catch (e: Exception) {
                e.printStackTrace()
                progressBar.visibility = View.GONE
                statusText.text = "エラーが発生しました: ${e.message}\nURL: $url"
                Toast.makeText(this@ReaderActivity, "小説の読み込みに失敗しました\nURL: $url", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePageNumber(current: Int, total: Int) {
        pageNumberText.text = "${current + 1} / $total"
    }

    private class BookPagerAdapter(
        private val book: Book,
        private val pages: List<BookPage>,
        private val fontSize: Float,
        private val lineSpacing: Float,
        private val charSpacing: Float,
        private val padLeft: Float,
        private val padRight: Float,
        private val padTop: Float,
        private val padBottom: Float
    ) : RecyclerView.Adapter<BookPagerAdapter.PageViewHolder>() {

        class PageViewHolder(val textView: VerticalTextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val textView = VerticalTextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.fontSize = this@BookPagerAdapter.fontSize
                this.lineSpacing = this@BookPagerAdapter.lineSpacing
                this.charSpacing = this@BookPagerAdapter.charSpacing
                this.setPadding(
                    this@BookPagerAdapter.padLeft.toInt(),
                    this@BookPagerAdapter.padTop.toInt(),
                    this@BookPagerAdapter.padRight.toInt(),
                    this@BookPagerAdapter.padBottom.toInt()
                )
                val typeface = ResourcesCompat.getFont(parent.context, R.font.ipam)
                this.setCustomTypeface(typeface)
            }
            return PageViewHolder(textView)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val page = pages[position]
            holder.textView.setPageData(book, page)
        }

        override fun getItemCount(): Int = pages.size
    }
}
