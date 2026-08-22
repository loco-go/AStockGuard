package com.locogo.astockguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.locogo.astockguard.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private val market = TencentMarketClient()
    private val ai = AiClient()
    private val types = listOf("CHATGPT_WEB", "RESPONSES", "CHAT_COMPLETIONS", "LOCAL")

    private val openImportFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)
        binding.spPrimaryType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        binding.spBackupType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        load()

        // 不设置 maxLength；大文本可滚动。剪贴板/文件导入会绕过输入法粘贴长度限制。
        binding.etImportText.filters = emptyArray()
        binding.etImportText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvImportLength.text = "当前文本：${s?.length ?: 0} 字符"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnPasteClipboard.setOnClickListener { importFromClipboard() }
        binding.btnImportFile.setOnClickListener { openImportFile.launch(arrayOf("application/json", "text/plain", "text/*", "*/*")) }
        binding.btnClearImport.setOnClickListener { binding.etImportText.text?.clear(); binding.tvImportResult.text = "尚未解析" }
        binding.btnParseImport.setOnClickListener { parseImport(binding.etImportText.text.toString()) }
        binding.btnSave.setOnClickListener { save(); binding.tvTestResult.text = "已保存：Token/Session/Cookie 使用 Android Keystore AES-GCM 加密。" }
        binding.btnTestMarket.setOnClickListener { testMarket() }
        binding.btnTestAi.setOnClickListener { testAi() }
    }

    private fun load() = with(binding) {
        etWatchCodes.setText(settings.watchCodes)
        etPositions.setText(settings.positionsText)
        etPositionRatio.setText(settings.positionRatio.toString())
        etRefreshSeconds.setText(settings.refreshSeconds.toString())
        spPrimaryType.setSelection(types.indexOf(settings.primaryType).coerceAtLeast(0))
        etPrimaryBaseUrl.setText(settings.primaryBaseUrl)
        etPrimaryApiKey.setText(settings.primaryApiKey)
        etSessionToken.setText(settings.primarySessionToken)
        etCookie.setText(settings.primaryCookie)
        etPrimaryModel.setText(settings.primaryModel)
        spBackupType.setSelection(types.indexOf(settings.backupType).coerceAtLeast(0))
        etBackupBaseUrl.setText(settings.backupBaseUrl)
        etBackupApiKey.setText(settings.backupApiKey)
        etBackupModel.setText(settings.backupModel)
        etExtraHeaders.setText(settings.extraHeadersJson)
    }

    private fun save() = with(binding) {
        settings.watchCodes = etWatchCodes.text.toString().trim()
        settings.positionsText = etPositions.text.toString().trim()
        settings.positionRatio = etPositionRatio.text.toString().toDoubleOrNull() ?: 0.0
        settings.refreshSeconds = etRefreshSeconds.text.toString().toIntOrNull() ?: 5
        settings.primaryType = spPrimaryType.selectedItem.toString()
        settings.primaryBaseUrl = etPrimaryBaseUrl.text.toString().trim().ifBlank { SettingsRepository.defaultEndpoint(settings.primaryType) }
        settings.primaryApiKey = etPrimaryApiKey.text.toString().trim()
        settings.primarySessionToken = etSessionToken.text.toString().trim()
        settings.primaryCookie = etCookie.text.toString().trim()
        settings.primaryModel = etPrimaryModel.text.toString().trim().ifBlank { "auto" }
        settings.extraHeadersJson = etExtraHeaders.text.toString().trim().ifBlank { "{}" }
        settings.backupType = spBackupType.selectedItem.toString()
        settings.backupBaseUrl = etBackupBaseUrl.text.toString().trim()
        settings.backupApiKey = etBackupApiKey.text.toString().trim()
        settings.backupModel = etBackupModel.text.toString().trim()
    }

    private fun importFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = cm.primaryClip ?: run {
            binding.tvImportResult.text = "剪贴板为空"
            return
        }
        val text = buildString {
            for (i in 0 until clip.itemCount) append(clip.getItemAt(i).coerceToText(this@SettingsActivity))
        }
        if (text.isBlank()) {
            binding.tvImportResult.text = "剪贴板没有可解析文本"
            return
        }
        // 直接解析，不必先塞进 EditText，可规避部分输入法/剪贴板粘贴截断。
        parseImport(text)
        binding.tvImportLength.text = "剪贴板导入：${text.length} 字符（已直接解析，未完整回填输入框）"
    }

    private fun importFromUri(uri: Uri) = lifecycleScope.launch {
        binding.tvImportResult.text = "读取文件中..."
        val text = runCatching {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        total += n
                        require(total <= 4_000_000) { "文件超过 4,000,000 字符" }
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                } ?: error("无法读取文件")
            }
        }.getOrElse {
            binding.tvImportResult.text = "文件读取失败：${it.message}"
            return@launch
        }
        parseImport(text)
        binding.tvImportLength.text = "文件导入：${text.length} 字符（已直接解析）"
    }

    private fun parseImport(text: String) {
        val c = runCatching { CredentialParser.parse(text) }.getOrElse {
            binding.tvImportResult.text = "解析失败：${it.message}"
            return
        }
        settings.applyImported(c)
        load()
        binding.tvImportResult.text = buildString {
            appendLine("已解析并加密保存到本机：type=${c.type}")
            if (c.type == "CHATGPT_WEB") appendLine("注意：WebView 模式不使用导入的 Token/Cookie 发私有 API 请求；请在 WebView 内正常登录。")
            appendLine("endpoint=${c.baseUrl}")
            appendLine("Token=${mask(c.apiKey)}")
            appendLine("Session=${mask(c.sessionToken)}")
            appendLine("Cookie=${if (c.cookie.isBlank()) "未识别" else "已识别"}")
            append("model=${c.model}")
        }
    }

    private fun testMarket() = lifecycleScope.launch {
        binding.tvTestResult.text = "测试腾讯行情..."
        try {
            binding.tvTestResult.text = "腾讯行情 OK\n${market.fetchQuotes(settings.allCodes().take(3)).joinToString("\n")}" 
        } catch (t: Throwable) {
            binding.tvTestResult.text = "行情失败：${t.message}"
        }
    }

    private fun testAi() {
        save()
        if (settings.primaryType == "CHATGPT_WEB") {
            binding.tvTestResult.text = "CHATGPT_WEB 使用 WebView 登录态：测试窗口会自动填入、自动发送，并在回复稳定后自动回传；若登录失效只需在 WebView 中手动登录。"
            startActivity(
                android.content.Intent(this, ChatGptWebActivity::class.java)
                    .putExtra(ChatGptWebActivity.EXTRA_PROMPT, "只回复：OK")
            )
            return
        }
        lifecycleScope.launch {
            binding.tvTestResult.text = "测试 AI..."
            try {
                binding.tvTestResult.text = "AI OK\n${ai.analyze(settings.primaryProvider(), settings.backupProvider(), "只回复：OK")}"
            } catch (t: Throwable) {
                binding.tvTestResult.text = "AI失败：${t.message}"
            }
        }
    }

    private fun mask(v: String): String = if (v.length < 12) {
        if (v.isBlank()) "未识别" else "***"
    } else v.take(6) + "…" + v.takeLast(4)
}
