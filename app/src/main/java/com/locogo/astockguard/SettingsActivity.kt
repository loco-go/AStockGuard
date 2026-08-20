package com.locogo.astockguard

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.locogo.astockguard.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private val ifind = IfindClient()
    private val ai = AiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsRepository(this)
        load()

        binding.btnSave.setOnClickListener {
            save()
            binding.tvTestResult.text = "已保存。API Key / refresh_token 使用 Android Keystore AES-GCM 加密保存。"
        }
        binding.btnTestIfind.setOnClickListener { testIfind() }
        binding.btnTestAi.setOnClickListener { testAi() }
    }

    private fun load() = with(binding) {
        etIfindRefreshToken.setText(settings.ifindRefreshToken)
        etWatchCodes.setText(settings.watchCodes)
        etPositions.setText(settings.positionsText)
        etPositionRatio.setText(settings.positionRatio.toString())
        etPrimaryBaseUrl.setText(settings.primaryBaseUrl)
        etPrimaryApiKey.setText(settings.primaryApiKey)
        etPrimaryModel.setText(settings.primaryModel)
        etBackupBaseUrl.setText(settings.backupBaseUrl)
        etBackupApiKey.setText(settings.backupApiKey)
        etBackupModel.setText(settings.backupModel)
        etExtraHeaders.setText(settings.extraHeadersJson)
    }

    private fun save() = with(binding) {
        settings.ifindRefreshToken = etIfindRefreshToken.text.toString().trim()
        settings.watchCodes = etWatchCodes.text.toString().trim()
        settings.positionsText = etPositions.text.toString().trim()
        settings.positionRatio = etPositionRatio.text.toString().toDoubleOrNull() ?: 0.0
        settings.primaryBaseUrl = etPrimaryBaseUrl.text.toString().trim().ifBlank { "https://api.openai.com/v1" }
        settings.primaryApiKey = etPrimaryApiKey.text.toString().trim()
        settings.primaryModel = etPrimaryModel.text.toString().trim().ifBlank { "gpt-5.6" }
        settings.backupBaseUrl = etBackupBaseUrl.text.toString().trim()
        settings.backupApiKey = etBackupApiKey.text.toString().trim()
        settings.backupModel = etBackupModel.text.toString().trim()
        settings.extraHeadersJson = etExtraHeaders.text.toString().trim().ifBlank { "{}" }
    }

    private fun testIfind() {
        save()
        lifecycleScope.launch {
            binding.btnTestIfind.isEnabled = false
            binding.tvTestResult.text = "测试 iFinD..."
            try {
                val token = ifind.ensureAccessToken(settings.ifindRefreshToken)
                val code = settings.allCodes().firstOrNull() ?: "000001.SZ"
                val prev = ifind.fetchPreviousCloses(token, listOf(code))
                val quote = ifind.fetchQuotes(token, listOf(code), prev).firstOrNull()
                binding.tvTestResult.text = "iFinD OK\n$quote"
            } catch (t: Throwable) {
                binding.tvTestResult.text = "iFinD 失败：${t.message}"
            } finally {
                binding.btnTestIfind.isEnabled = true
            }
        }
    }

    private fun testAi() {
        save()
        lifecycleScope.launch {
            binding.btnTestAi.isEnabled = false
            binding.tvTestResult.text = "测试 AI..."
            try {
                val result = ai.analyze(settings.primaryProvider(), settings.backupProvider(), "只回复：OK")
                binding.tvTestResult.text = "AI OK\n$result"
            } catch (t: Throwable) {
                binding.tvTestResult.text = "AI 失败：${t.message}"
            } finally {
                binding.btnTestAi.isEnabled = true
            }
        }
    }
}
