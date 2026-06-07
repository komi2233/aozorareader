package com.github.komi2233.aozorareader.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * 青空文庫のZIPデータを取得・デコードするダウンローダー。
 */
class AozoraDownloader(private val client: OkHttpClient = OkHttpClient()) {

    /**
     * 指定したURLからZIPファイルをダウンロードし、指定したローカルファイルに保存する。
     * バックグラウンドスレッドで実行する必要があります。
     */
    fun downloadZip(url: String, outputFile: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed to download file: $response")
            val body = response.body ?: throw Exception("Response body is empty")
            
            FileOutputStream(outputFile).use { out ->
                body.byteStream().copyTo(out)
            }
        }
    }

    /**
     * 青空文庫の全作品リスト（UTF-8 CSV）をZIPから解凍してデコードする。
     */
    fun unzipCsvAndDecode(file: File): String {
        file.inputStream().use { fileInput ->
            ZipInputStream(fileInput).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                        return zipInput.bufferedReader(Charsets.UTF_8).readText()
                    }
                    entry = zipInput.nextEntry
                }
            }
        }
        throw Exception("CSV file not found in the ZIP")
    }

    /**
     * ZIPまたはEPUBファイルを解凍し、中身のテキストを取得する。
     * ZIPの場合は .txt を、EPUBの場合は内部の XHTML からテキストを抽出する。
     */
    fun unzipAndDecode(file: File): String {
        val isEpub = file.name.endsWith(".epub", ignoreCase = true)
        
        file.inputStream().use { fileInput ->
            ZipInputStream(fileInput).use { zipInput ->
                val content = StringBuilder()
                var entry = zipInput.nextEntry
                var found = false
                
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (!isEpub && entry.name.endsWith(".txt", ignoreCase = true)) {
                            val reader = BufferedReader(InputStreamReader(zipInput, Charset.forName("MS932")))
                            var line = reader.readLine()
                            while (line != null) {
                                content.append(line).append("\n")
                                line = reader.readLine()
                            }
                            return content.toString()
                        } else if (isEpub && (entry.name.endsWith(".xhtml", ignoreCase = true) || entry.name.endsWith(".html", ignoreCase = true))) {
                            // EPUB内のXHTMLを簡易的にテキスト抽出（タグ除去）
                            val reader = BufferedReader(InputStreamReader(zipInput, Charsets.UTF_8))
                            var line = reader.readLine()
                            while (line != null) {
                                // HTMLタグを簡易的に除去（ルビタグなどは無視してテキストのみ抽出）
                                val textOnly = line.replace(Regex("<[^>]*>"), "")
                                if (textOnly.isNotBlank()) {
                                    content.append(textOnly).append("\n")
                                }
                                line = reader.readLine()
                            }
                            found = true
                        }
                    }
                    entry = zipInput.nextEntry
                }
                if (found) return content.toString()
            }
        }
        throw Exception("No readable content found in the file")
    }

    /**
     * 指定された人物IDと作品IDをもとに、図書カードのHTMLから実際のテキストZIPまたはEPUBのダウンロードURLを取得します。
     */
    fun getZipUrlFromCard(personId: String, bookId: String): String {
        val formattedPersonId = personId.padStart(6, '0')
        val pageBookId = bookId.dropWhile { it == '0' }
        val cardUrl = "https://www.aozora.gr.jp/cards/$formattedPersonId/card$pageBookId.html"

        val request = Request.Builder().url(cardUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed to load card page: $response")
            val html = response.body?.string() ?: throw Exception("Card page body is empty")
            
            // ZIPまたはEPUBのリンクを探す
            // href="files/..." や href="https://..." 両方に対応
            val pattern = java.util.regex.Pattern.compile("href=\"([^\"]+\\.(?:zip|epub))\"")
            val matcher = pattern.matcher(html)
            
            // 複数のリンクがある場合、通常のテキストZIP（ruby入り）を優先し、なければEPUBなどを探す
            val urls = mutableListOf<String>()
            while (matcher.find()) {
                matcher.group(1)?.let { urls.add(it) }
            }
            
            val selectedUrl = urls.find { it.contains("ruby") && it.endsWith(".zip") }
                ?: urls.find { it.endsWith(".zip") }
                ?: urls.find { it.endsWith(".epub") }
                ?: throw Exception("Download link not found in card page")

            return if (selectedUrl.startsWith("http")) {
                // HTTPリンクをHTTPSに置換（Aozora Bunkoのサーバーが対応している前提）
                if (selectedUrl.startsWith("http://")) {
                    selectedUrl.replace("http://", "https://")
                } else {
                    selectedUrl
                }
            } else {
                // 相対パスを解決
                val cleanPath = selectedUrl.removePrefix("./")
                "https://www.aozora.gr.jp/cards/$formattedPersonId/$cleanPath"
            }
        }
    }
}
