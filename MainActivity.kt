package com.downlypro.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var pendingSharedText: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        pendingSharedText = extractSharedText(intent)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pendingSharedText?.let {
                    val safe = JSONObject.quote(it)
                    web.evaluateJavascript("document.getElementById('url').value=$safe; window.onSharedLink && window.onSharedLink();", null)
                    pendingSharedText = null
                }
            }
        }
        web.addJavascriptInterface(DownloadBridge(), "Downly")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedText = extractSharedText(intent)
        if (pendingSharedText != null && ::web.isInitialized) {
            val safe = JSONObject.quote(pendingSharedText)
            web.evaluateJavascript("document.getElementById('url').value=$safe; window.onSharedLink && window.onSharedLink();", null)
            pendingSharedText = null
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private inner class DownloadBridge {
        @JavascriptInterface fun startDownload(url: String, quality: String) {
            if (!busy.compareAndSet(false, true)) return
            worker.execute {
                val job = System.currentTimeMillis().toString()
                val outDir = File(cacheDir, "downly/$job").apply { mkdirs() }
                val statusFile = File(cacheDir, "downly/$job/status.json")
                statusFile.writeText("""{"state":"analyzing","percent":0,"message":"Iniciando..."}""")
                val py = Python.getInstance().getModule("downloader")
                try {
                    py.callAttr("start_download", url, quality, outDir.absolutePath, statusFile.absolutePath)
                    var finalState = ""
                    for (attempt in 0 until 7200) {
                        val status = try { JSONObject(statusFile.readText()) } catch (_: Exception) { JSONObject() }
                        val state = status.optString("state", "analyzing")
                        val pct = if (status.isNull("percent")) -1.0 else status.optDouble("percent", -1.0)
                        val message = status.optString("message", "Processando...")
                        sendProgress(state, pct, message)
                        if (state == "finished" || state == "error") {
                            finalState = state
                            if (state == "finished") {
                                val source = File(status.optString("filepath"))
                                val displayName = status.optString("filename", source.name)
                                val saved = saveToDownloads(source, displayName)
                                sendProgress("finished", 100.0, if (saved) "Salvo em Downloads/Downly Pro/$displayName" else "Download concluído, mas não foi possível salvar na pasta Downloads.")
                            }
                            break
                        }
                        Thread.sleep(500)
                    }
                    if (finalState.isEmpty()) sendProgress("error", -1.0, "Tempo limite excedido. Verifique a conexão e tente novamente.")
                } catch (e: Exception) {
                    sendProgress("error", -1.0, e.message ?: "Erro durante o download.")
                } finally {
                    busy.set(false)
                }
            }
        }

        @JavascriptInterface fun pasteFromClipboard() {
            runOnUiThread {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                if (!text.isNullOrBlank()) {
                    val safe = JSONObject.quote(text)
                    web.evaluateJavascript("document.getElementById('url').value=$safe", null)
                }
            }
        }
    }

    private fun sendProgress(state: String, percent: Double, message: String) {
        val pctJs = if (percent < 0) "null" else percent.toString()
        val stateJs = JSONObject.quote(state)
        val msgJs = JSONObject.quote(message)
        runOnUiThread {
            web.evaluateJavascript("window.onNativeProgress && window.onNativeProgress($stateJs,$pctJs,$msgJs)", null)
        }
    }

    private fun saveToDownloads(source: File, name: String): Boolean {
        if (!source.exists()) return false
        return try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Downly Pro")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { output -> FileInputStream(source).use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) { false }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4","m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "jpg","jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        worker.shutdown()
        super.onDestroy()
    }
}
