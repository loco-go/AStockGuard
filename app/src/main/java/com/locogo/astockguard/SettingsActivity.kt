package com.locogo.astockguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doOnTextChanged
import com.locogo.astockguard.databinding.ActivitySettingsBinding
import com.locogo.astockguard.integration.ths.ThsTradeSyncActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private val market = TencentMarketClient()
    private val ai = AiClient()
    private val aiTypes = listOf("CHATGPT_WEB", "RESPONSES", "CHAT_COMPLETIONS", "LOCAL")
    private val level2Types = listOf("MOCK", "HTTP_JSON")

    private val openImportFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFromUri(it) } }
    private val createBackupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(::writeBackup) }
    private val openBackupFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::restoreBackup) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        settings = appContainer.settings
        binding.spPrimaryType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aiTypes)
        binding.spBackupType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aiTypes)
        binding.spLevel2Type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, level2Types)
        load()

        binding.etImportText.filters = emptyArray()
        binding.etImportText.doOnTextChanged { text, _, _, _ ->
            binding.tvImportLength.text = "当前文本：${text?.length ?: 0} 字符"
        }
        binding.btnPasteClipboard.setOnClickListener { importFromClipboard() }
        binding.btnImportFile.setOnClickListener { openImportFile.launch(arrayOf("application/json", "text/plain", "text/*", "*/*")) }
        binding.btnClearImport.setOnClickListener { binding.etImportText.text?.clear(); binding.tvImportResult.text = "尚未解析" }
        binding.btnParseImport.setOnClickListener { parseImport(binding.etImportText.text.toString()) }
        binding.btnSave.setOnClickListener { save(); binding.tvTestResult.text = "已保存：敏感凭据继续使用 Android Keystore AES-GCM 加密。" }
        binding.btnTestMarket.setOnClickListener { testMarket() }
        binding.btnTestLevel2.setOnClickListener { testLevel2() }
        binding.btnTestAi.setOnClickListener { testAi() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_THS_SYNC, 0, "同花顺交易同步").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_BACKUP, 1, "导出备份").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_RESTORE, 2, "恢复备份").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_THS_SYNC -> { startActivity(Intent(this, ThsTradeSyncActivity::class.java)); true }
        MENU_BACKUP -> { createBackupFile.launch("股衡备份-${System.currentTimeMillis()}.json"); true }
        MENU_RESTORE -> { openBackupFile.launch(arrayOf("application/json", "text/plain")); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun writeBackup(uri: Uri) = lifecycleScope.launch {
        binding.tvTestResult.text = "正在生成备份…"
        runCatching {
            val json = appContainer.backupManager.exportJson()
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) } ?: error("无法打开备份文件") }
        }.onSuccess { binding.tvTestResult.text = "备份成功。API Key、Cookie、Session Token、Level2 Token 均未导出。" }
            .onFailure { binding.tvTestResult.text = "备份失败：${it.message}" }
    }

    private fun restoreBackup(uri: Uri) = lifecycleScope.launch {
        binding.tvTestResult.text = "正在校验并恢复备份…"
        runCatching {
            val text = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val sb = StringBuilder(); val buf = CharArray(16 * 1024)
                    while (true) { val n = reader.read(buf); if (n <= 0) break; sb.append(buf, 0, n); require(sb.length <= 20_000_000) { "备份文件过大" } }
                    sb.toString()
                } ?: error("无法读取备份文件")
            }
            appContainer.backupManager.importJson(text)
        }.onSuccess { load(); binding.tvTestResult.text = "恢复成功。所有安全凭据保持当前设备原值。" }
            .onFailure { binding.tvTestResult.text = "恢复失败：${it.message}" }
    }

    private fun load() = with(binding) {
        etWatchCodes.setText(settings.watchCodes)
        etPositions.setText(settings.positionsText)
        etPositionRatio.setText(settings.positionRatio.toString())
        etCashBalance.setText(settings.cashBalance?.toString().orEmpty())
        etRefreshSeconds.setText(settings.refreshSeconds.toString())
        swNewsEnabled.isChecked = settings.newsEnabled
        etNewsRefreshMinutes.setText(settings.newsRefreshMinutes.toString())
        etNewsSources.setText(settings.newsSourcesText)
        spLevel2Type.setSelection(level2Types.indexOf(settings.level2ProviderType).coerceAtLeast(0))
        etLevel2BaseUrl.setText(settings.level2BaseUrl)
        etLevel2Token.setText(settings.level2ApiToken)
        spPrimaryType.setSelection(aiTypes.indexOf(settings.primaryType).coerceAtLeast(0))
        etPrimaryBaseUrl.setText(settings.primaryBaseUrl)
        etPrimaryApiKey.setText(settings.primaryApiKey)
        etSessionToken.setText(settings.primarySessionToken)
        etCookie.setText(settings.primaryCookie)
        etPrimaryModel.setText(settings.primaryModel)
        spBackupType.setSelection(aiTypes.indexOf(settings.backupType).coerceAtLeast(0))
        etBackupBaseUrl.setText(settings.backupBaseUrl)
        etBackupApiKey.setText(settings.backupApiKey)
        etBackupModel.setText(settings.backupModel)
        etExtraHeaders.setText(settings.extraHeadersJson)
    }

    private fun save() = with(binding) {
        settings.watchCodes = etWatchCodes.text.toString().trim()
        settings.positionsText = etPositions.text.toString().trim()
        settings.positionRatio = etPositionRatio.text.toString().toDoubleOrNull() ?: 0.0
        settings.cashBalance = etCashBalance.text.toString().trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        settings.refreshSeconds = etRefreshSeconds.text.toString().toIntOrNull() ?: 5
        settings.newsEnabled = swNewsEnabled.isChecked
        settings.newsRefreshMinutes = etNewsRefreshMinutes.text.toString().toIntOrNull() ?: 15
        settings.newsSourcesText = etNewsSources.text.toString().trim().ifBlank { SettingsRepository.DEFAULT_NEWS_SOURCES }
        settings.level2ProviderType = spLevel2Type.selectedItem.toString()
        settings.level2BaseUrl = etLevel2BaseUrl.text.toString().trim()
        settings.level2ApiToken = etLevel2Token.text.toString().trim()
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

    private fun testLevel2() {
        save()
        lifecycleScope.launch {
            binding.tvTestResult.text = "测试 Level2..."
            val code = settings.allCodes().firstOrNull() ?: "000001.SZ"
            runCatching { appContainer.level2Repository.snapshot(code) }
                .onSuccess { s ->
                    binding.tvTestResult.text = if (s == null) "Level2 无数据" else buildString {
                        append("Level2 OK · ${s.source}")
                        if (s.simulated) append(" · MOCK模拟") else append(" · 真实Provider")
                        if (s.stale) append(" · STALE缓存")
                        append("\n$code 买档=${s.bids.size} 卖档=${s.asks.size} 成交=${s.trades.size}")
                    }
                }
                .onFailure { binding.tvTestResult.text = "Level2失败：${it.message}" }
        }
    }

    private fun importFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData = cm.primaryClip ?: run { binding.tvImportResult.text = "剪贴板为空"; return }
        val text = buildString { for (i in 0 until clip.itemCount) append(clip.getItemAt(i).coerceToText(this@SettingsActivity)) }
        if (text.isBlank()) { binding.tvImportResult.text = "剪贴板没有可解析文本"; return }
        parseImport(text); binding.tvImportLength.text = "剪贴板导入：${text.length} 字符（已直接解析，未完整回填输入框）"
    }

    private fun importFromUri(uri: Uri) = lifecycleScope.launch {
        binding.tvImportResult.text = "读取文件中..."
        val text = runCatching { withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val sb = StringBuilder(); val buf = CharArray(16 * 1024); var total = 0
            while (true) { val n = reader.read(buf); if (n <= 0) break; total += n; require(total <= 4_000_000) { "文件超过 4,000,000 字符" }; sb.append(buf, 0, n) }; sb.toString()
        } ?: error("无法读取文件") } }.getOrElse { binding.tvImportResult.text = "文件读取失败：${it.message}"; return@launch }
        parseImport(text); binding.tvImportLength.text = "文件导入：${text.length} 字符（已直接解析）"
    }

    private fun parseImport(text: String) {
        val c = runCatching { CredentialParser.parse(text) }.getOrElse { binding.tvImportResult.text = "解析失败：${it.message}"; return }
        settings.applyImported(c); load()
        binding.tvImportResult.text = buildString {
            appendLine("已解析并加密保存到本机：type=${c.type}")
            if (c.type == "CHATGPT_WEB") appendLine("注意：WebView 模式不使用导入的 Token/Cookie 发私有 API 请求；请在 WebView 内正常登录。")
            appendLine("endpoint=${c.baseUrl}"); appendLine("Token=${mask(c.apiKey)}"); appendLine("Session=${mask(c.sessionToken)}")
            appendLine("Cookie=${if (c.cookie.isBlank()) "未识别" else "已识别"}"); append("model=${c.model}")
        }
    }

    private fun testMarket() = lifecycleScope.launch {
        binding.tvTestResult.text = "测试腾讯行情..."
        try { binding.tvTestResult.text = "腾讯行情 OK\n${market.fetchQuotes(settings.allCodes().take(3)).joinToString("\n")}" }
        catch (t: Throwable) { binding.tvTestResult.text = "行情失败：${t.message}" }
    }

    private fun testAi() {
        save()
        if (settings.primaryType == "CHATGPT_WEB") {
            binding.tvTestResult.text = "CHATGPT_WEB 使用 WebView 登录态：正常分析隐藏运行；若登录失效只需在 WebView 中手动登录。"
            startActivity(Intent(this, ChatGptWebActivity::class.java).putExtra(ChatGptWebActivity.EXTRA_PROMPT, "只回复：OK")); return
        }
        lifecycleScope.launch {
            binding.tvTestResult.text = "测试 AI..."
            try { binding.tvTestResult.text = "AI OK\n${ai.analyze(settings.primaryProvider(), settings.backupProvider(), "只回复：OK")}" }
            catch (t: Throwable) { binding.tvTestResult.text = "AI失败：${t.message}" }
        }
    }

    private fun mask(v: String): String = if (v.length < 12) { if (v.isBlank()) "未识别" else "***" } else v.take(6) + "…" + v.takeLast(4)

    companion object {
        private const val MENU_THS_SYNC = 1000
        private const val MENU_BACKUP = 1001
        private const val MENU_RESTORE = 1002
    }
}
