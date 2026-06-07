package com.github.komi2233.aozorareader

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.github.komi2233.aozorareader.data.AozoraDownloader
import com.github.komi2233.aozorareader.data.AozoraParser
import com.github.komi2233.aozorareader.data.db.AppDatabase
import com.github.komi2233.aozorareader.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsProvider: SettingsProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = SettingsProvider(this)
        if (settings.isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        super.onCreate(savedInstanceState)
        settingsProvider = settings

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // --- フォントサイズ設定 ---
        rootLayout.addView(TextView(this).apply {
            text = "フォントサイズ"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        })

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 0, 0, 48)
        }

        val sizes = mapOf(
            "小さめ" to SettingsProvider.FONT_SIZE_SMALL,
            "ふつう" to SettingsProvider.FONT_SIZE_MEDIUM,
            "大きめ" to SettingsProvider.FONT_SIZE_LARGE
        )

        sizes.forEach { (label, size) ->
            val radioButton = RadioButton(this).apply {
                text = label
                id = size.toInt()
                isChecked = settingsProvider.fontSize == size
            }
            radioGroup.addView(radioButton)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            settingsProvider.fontSize = checkedId.toFloat()
            Toast.makeText(this, "フォントサイズを変更しました", Toast.LENGTH_SHORT).show()
        }
        rootLayout.addView(radioGroup)

        // --- ダークモード設定 ---
        val darkModeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 64)
        }

        darkModeLayout.addView(TextView(this).apply {
            text = "ダークモード"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val darkModeSwitch = SwitchCompat(this).apply {
            isChecked = settingsProvider.isDarkMode
            setOnCheckedChangeListener { _, isChecked ->
                settingsProvider.isDarkMode = isChecked
                if (isChecked) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
            }
        }
        darkModeLayout.addView(darkModeSwitch)
        rootLayout.addView(darkModeLayout)

        // --- CSV更新 ---
        rootLayout.addView(TextView(this).apply {
            text = "データ更新"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        })

        val updateButton = Button(this).apply {
            text = "作品リストを再ダウンロードして更新"
            setOnClickListener {
                updateCsv(this)
            }
        }
        rootLayout.addView(updateButton)

        setContentView(rootLayout)
    }

    private fun updateCsv(button: Button) {
        lifecycleScope.launch {
            try {
                button.isEnabled = false
                Toast.makeText(this@SettingsActivity, "更新を開始します...", Toast.LENGTH_SHORT).show()
                val downloader = AozoraDownloader()
                val db = AppDatabase.getDatabase(this@SettingsActivity)
                val dao = db.bookDao()

                val csvFile = File(filesDir, "list_person_all_utf8.csv")
                val zipFile = File(cacheDir, "list_person_all_utf8.zip")
                val listUrl = "https://www.aozora.gr.jp/index_pages/list_person_all_utf8.zip"

                withContext(Dispatchers.IO) {
                    downloader.downloadZip(listUrl, zipFile)
                    val csvContent = downloader.unzipCsvAndDecode(zipFile)
                    csvFile.writeText(csvContent)
                    zipFile.delete()

                    val books = AozoraParser.parseCsv(csvContent)
                    val entities = books.map {
                        BookEntity(it.personId, it.bookId, it.title, it.author)
                    }
                    
                    entities.chunked(1000).forEach { chunk ->
                        dao.insertAll(chunk)
                    }
                }

                Toast.makeText(this@SettingsActivity, "更新が完了しました", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SettingsActivity, "更新に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                button.isEnabled = true
            }
        }
    }
}
