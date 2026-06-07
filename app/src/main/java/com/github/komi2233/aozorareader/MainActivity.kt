package com.github.komi2233.aozorareader

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.github.komi2233.aozorareader.SettingsProvider
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.komi2233.aozorareader.data.AozoraDownloader
import com.github.komi2233.aozorareader.data.AozoraParser
import com.github.komi2233.aozorareader.data.db.AppDatabase
import com.github.komi2233.aozorareader.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 青空文庫の全作品リスト（CSV）をロードし、検索・一覧表示を行うメイン画面。
 * トップ画面はダウンロード済みのみ表示し、検索入力で全作品から検索できます。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var searchEditText: EditText
    private lateinit var topRow: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val downloader = AozoraDownloader()
    private lateinit var adapter: BookAdapter
    
    private var searchJob: Job? = null
    private var currentFilterJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = SettingsProvider(this)
        if (settings.isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        super.onCreate(savedInstanceState)

        // 動的なLinearLayoutの構築
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 背景色をテーマに合わせる
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.windowBackground, outValue, true)
            if (outValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                outValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                setBackgroundColor(outValue.data)
            } else {
                setBackgroundResource(outValue.resourceId)
            }
        }

        // 検索ボックス
        topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(48, 32, 48, 16)
            }
            visibility = View.GONE // ロード完了まで非表示
        }

        searchEditText = EditText(this).apply {
            hint = "作品名または著者名で検索..."
            textSize = 16f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsButton = Button(this).apply {
            text = "設定"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 16
            }
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        topRow.addView(searchEditText)
        topRow.addView(settingsButton)

        // RecyclerView
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@MainActivity)
            visibility = View.GONE
        }

        // ロード画面コンテナ
        val centerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        progressBar = ProgressBar(this).apply {
            visibility = View.VISIBLE
        }

        statusText = TextView(this).apply {
            text = "作品リストを準備中..."
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }

        centerContainer.addView(progressBar)
        centerContainer.addView(statusText)

        rootLayout.addView(topRow)
        rootLayout.addView(recyclerView)
        rootLayout.addView(centerContainer)

        setContentView(rootLayout)

        adapter = BookAdapter(emptyList(), 
            onClick = { bookEntity ->
                onBookSelected(bookEntity)
            },
            onLongClick = { bookEntity ->
                if (bookEntity.isDownloaded) {
                    showDeleteConfirmation(bookEntity)
                }
            }
        )
        recyclerView.adapter = adapter

        // DBの初期化とロードの開始
        initAndLoadData(centerContainer)
    }

    private fun initAndLoadData(loadingView: View) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@MainActivity)
                val dao = db.bookDao()

                if (dao.getCount() == 0) {
                    val csvFile = File(filesDir, "list_person_all_utf8.csv")
                    val zipFile = File(cacheDir, "list_person_all_utf8.zip")
                    val listUrl = "https://www.aozora.gr.jp/index_pages/list_person_all_utf8.zip"

                    if (!csvFile.exists()) {
                        statusText.text = "作品リストをダウンロード中..."
                        withContext(Dispatchers.IO) {
                            downloader.downloadZip(listUrl, zipFile)
                            val csvContent = downloader.unzipCsvAndDecode(zipFile)
                            csvFile.writeText(csvContent)
                            zipFile.delete()
                        }
                    }

                    statusText.text = "作品リストをデータベースに登録中..."
                    withContext(Dispatchers.Default) {
                        val content = csvFile.readText()
                        val books = AozoraParser.parseCsv(content)
                        val entities = books.map { 
                            BookEntity(it.personId, it.bookId, it.title, it.author)
                        }
                        // 15,000件程度なら一括インサート可能だが、念のため分割
                        entities.chunked(1000).forEach { chunk ->
                            dao.insertAll(chunk)
                        }
                    }
                }

                // UIの切り替え
                loadingView.visibility = View.GONE
                topRow.visibility = View.VISIBLE
                recyclerView.visibility = View.VISIBLE

                // 初回：ダウンロード済みのみ表示
                observeBooks("")

                // 検索テキストの監視
                searchEditText.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s.toString()
                        searchJob?.cancel()
                        searchJob = lifecycleScope.launch {
                            delay(300)
                            observeBooks(query)
                        }
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

            } catch (e: Exception) {
                e.printStackTrace()
                progressBar.visibility = View.GONE
                val listUrl = "https://www.aozora.gr.jp/index_pages/list_person_all_utf8.zip"
                statusText.text = "作品リストの読み込みに失敗しました:\n${e.message}\nURL: $listUrl"
            }
        }
    }

    private fun observeBooks(query: String) {
        currentFilterJob?.cancel()
        currentFilterJob = lifecycleScope.launch {
            val dao = AppDatabase.getDatabase(this@MainActivity).bookDao()
            val flow = if (query.isEmpty()) {
                dao.getDownloadedBooks()
            } else {
                dao.searchBooks(query)
            }
            
            flow.collectLatest { books ->
                adapter.updateData(books)
            }
        }
    }

    private fun onBookSelected(book: BookEntity) {
        // キーボードを閉じる
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)

        Toast.makeText(this, "『${book.title}』の情報を取得中...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                // 図書カードHTMLをロードしてZIPのダウンロードURLを抽出する
                val zipUrl = withContext(Dispatchers.IO) {
                    downloader.getZipUrlFromCard(book.personId, book.bookId)
                }

                // リーダー画面の起動
                val intent = Intent(this@MainActivity, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_ZIP_URL, zipUrl)
                    // 追加：DB更新用にIDを渡す
                    putExtra("extra_person_id", book.personId)
                    putExtra("extra_book_id", book.bookId)
                }
                startActivity(intent)
                
            } catch (e: Exception) {
                e.printStackTrace()
                val cardUrl = "https://www.aozora.gr.jp/cards/${book.personId.padStart(6, '0')}/card${book.bookId.dropWhile { it == '0' }}.html"
                Toast.makeText(this@MainActivity, "ZIPファイルのURL取得に失敗しました\nURL: $cardUrl", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDeleteConfirmation(book: BookEntity) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("本の削除")
            .setMessage("『${book.title}』のダウンロード済みデータを削除しますか？\n(履歴は残りますが、本文を再度ダウンロードする必要があります)")
            .setPositiveButton("削除") { _, _ ->
                deleteBookData(book)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteBookData(book: BookEntity) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@MainActivity)
                val dao = db.bookDao()
                
                // データをリセット（本文を削除し、DLフラグを倒す）
                val updatedBook = book.copy(
                    isDownloaded = false,
                    rawText = null
                    // lastReadPosition 等は保持する（またDLした時に続きから読めるように）
                )
                dao.updateBook(updatedBook)
                
                Toast.makeText(this@MainActivity, "削除しました", Toast.LENGTH_SHORT).show()
                // observeBooks("") などでリストが自動更新されるはず（Flowをcollectしているので）
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "削除に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private class BookAdapter(
        private var items: List<BookEntity>,
        private val onClick: (BookEntity) -> Unit,
        private val onLongClick: (BookEntity) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.ViewHolder>() {

        class ViewHolder(val view: View, val titleText: TextView, val authorText: TextView, val statusText: TextView) : RecyclerView.ViewHolder(view)

        fun updateData(newItems: List<BookEntity>) {
            this.items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val context = parent.context
            
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(48, 36, 48, 36)
                
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }

            val titleView = TextView(context).apply {
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val authorView = TextView(context).apply {
                textSize = 14f
                setPadding(0, 8, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusView = TextView(context).apply {
                textSize = 12f
                setTextColor(android.graphics.Color.BLUE)
                setPadding(16, 8, 0, 0)
            }

            row.addView(authorView)
            row.addView(statusView)

            container.addView(titleView)
            container.addView(row)

            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    2
                )
                setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
            }
            
            val itemWrapper = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(container)
                addView(divider)
            }

            return ViewHolder(itemWrapper, titleView, authorView, statusView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.titleText.text = item.title
            holder.authorText.text = item.author
            
            if (item.isDownloaded) {
                val size = item.rawText?.toByteArray()?.size ?: 0
                val sizeText = when {
                    size >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1fMB", size / (1024f * 1024f))
                    size >= 1024 -> "${size / 1024}KB"
                    else -> "${size}B"
                }
                holder.statusText.text = sizeText
                holder.statusText.visibility = View.VISIBLE
            } else {
                holder.statusText.text = ""
                holder.statusText.visibility = View.GONE
            }

            holder.view.setOnClickListener {
                onClick(item)
            }
            holder.view.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
