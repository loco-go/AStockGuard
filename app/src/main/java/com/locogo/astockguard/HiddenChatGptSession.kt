package com.locogo.astockguard

/*
 * 文件职责：维护隐藏 WebView 的 ChatGPT 可选自动化会话；正常路径不可打扰用户，登录、验证或 DOM 失效才转到可见恢复页。
 * 架构边界：修改时保持单向依赖和既有安全边界；敏感信息不得进入日志、备份或通知内容。
 * 风险说明：本应用提供交易研究与决策辅助，不执行真实账户自动委托；任何历史统计或提示都不构成收益保证。
 */

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the already-verified ChatGPT DOM automation without navigating away from MainActivity.
 * The WebView remains attached and VISIBLE at 1x1 px with near-zero alpha so Chromium keeps rendering/JS active.
 * Any login/DOM/network failure is escalated to the visible ChatGptWebActivity by the caller.
 */
class HiddenChatGptSession(
    private val activity: Activity,
    private val settingsRepo: SettingsRepository,
    private val onStatus: (String) -> Unit,
    private val onCompleted: (prompt: String, answer: String) -> Unit,
    private val onRequiresManual: (prompt: String, reason: String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val webView = WebView(activity)

    private var pendingPrompt = ""
    private var composerAttempts = 0
    private var sendAttempts = 0
    private var answerPollAttempts = 0
    private var baselineAssistantCount = 0
    private var baselineLastAssistantText = ""
    private var lastObservedAnswer = ""
    private var stableAnswerPolls = 0
    private var promptFilled = false
    private var promptSent = false
    private var finished = false
    private var manualRequested = false

    init {
        configureWebView()
        val params = FrameLayout.LayoutParams(1, 1, Gravity.END or Gravity.BOTTOM)
        webView.alpha = 0.01f
        webView.visibility = View.VISIBLE
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        activity.addContentView(webView, params)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setOffscreenPreRaster(true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!finished) onStatus("AI后台页面加载中…")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                val u = url.orEmpty()
                if (isConversationUrl(u)) settingsRepo.chatGptConversationUrl = u
                if (finished || pendingPrompt.isBlank()) return
                if (u.contains("chatgpt.com", ignoreCase = true)) {
                    onStatus("AI后台会话已加载，正在自动发送…")
                    handler.postDelayed({ checkComposerAndSend() }, 250L)
                } else if (looksLikeLogin(u)) {
                    requireManual("ChatGPT 登录状态已失效")
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !finished) {
                    requireManual("WebView 主页面错误：${error?.errorCode} ${error?.description}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && !finished && (errorResponse?.statusCode ?: 0) >= 400) {
                    requireManual("ChatGPT 页面 HTTP ${errorResponse?.statusCode}")
                }
            }
        }
    }

    fun analyze(prompt: String) {
        resetForNewTask(prompt)
        onStatus("AI后台分析启动…")
        val saved = settingsRepo.chatGptConversationUrl.takeIf(::isConversationUrl)
        webView.loadUrl(saved ?: ChatGptWebActivity.CHATGPT_HOME)
    }

    private fun resetForNewTask(prompt: String) {
        handler.removeCallbacksAndMessages(null)
        pendingPrompt = prompt
        composerAttempts = 0
        sendAttempts = 0
        answerPollAttempts = 0
        baselineAssistantCount = 0
        baselineLastAssistantText = ""
        lastObservedAnswer = ""
        stableAnswerPolls = 0
        promptFilled = false
        promptSent = false
        finished = false
        manualRequested = false
    }

    private fun checkComposerAndSend() {
        if (finished || promptSent || manualRequested) return
        composerAttempts++
        val script = """
            (function(){
              try {
                const composer = document.querySelector('#prompt-textarea') ||
                  document.querySelector('[contenteditable="true"][role="textbox"]') ||
                  document.querySelector('textarea');
                const assistants = Array.from(document.querySelectorAll('[data-message-author-role="assistant"]'));
                const last = assistants.length ? assistants[assistants.length - 1] : null;
                const body = last ? (last.querySelector('.markdown') || last) : null;
                return JSON.stringify({
                  ready: !!composer,
                  url: location.href,
                  title: document.title,
                  assistantCount: assistants.length,
                  lastAssistantText: body ? (body.innerText || body.textContent || '').trim() : ''
                });
              } catch(e) { return JSON.stringify({ready:false,error:String(e),url:location.href}); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            if (finished || manualRequested) return@evaluateJavascript
            val state = parseJsJson(raw)
            val url = state.optString("url")
            if (isConversationUrl(url)) settingsRepo.chatGptConversationUrl = url
            if (looksLikeLogin(url)) {
                requireManual("需要重新登录 ChatGPT")
                return@evaluateJavascript
            }
            if (state.optBoolean("ready", false)) {
                baselineAssistantCount = state.optInt("assistantCount", 0)
                baselineLastAssistantText = state.optString("lastAssistantText")
                fillPrompt()
            } else if (composerAttempts < MAX_COMPOSER_ATTEMPTS) {
                onStatus("AI后台等待输入框… ${composerAttempts}/${MAX_COMPOSER_ATTEMPTS}")
                handler.postDelayed({ checkComposerAndSend() }, 750L)
            } else {
                requireManual("长时间未检测到 ChatGPT 输入框，可能登录失效或网页 DOM 已变化")
            }
        }
    }

    private fun fillPrompt() {
        if (promptFilled || promptSent || finished) return
        val quoted = JSONObject.quote(pendingPrompt)
        val script = """
            (function() {
              try {
                const text = $quoted;
                const el = document.querySelector('#prompt-textarea') ||
                  document.querySelector('[contenteditable="true"][role="textbox"]') ||
                  document.querySelector('textarea');
                if (!el) return 'NO_EDITOR';
                el.focus();
                if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
                  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                  if (setter) setter.call(el, text); else el.value = text;
                  el.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:text}));
                  el.dispatchEvent(new Event('change', {bubbles:true}));
                } else {
                  const selection = window.getSelection();
                  const range = document.createRange();
                  range.selectNodeContents(el);
                  selection.removeAllRanges(); selection.addRange(range);
                  try { document.execCommand('delete', false, null); } catch(e) { el.textContent=''; }
                  let inserted = false;
                  try { inserted = document.execCommand('insertText', false, text); } catch(e) {}
                  if (!inserted) {
                    el.textContent = text;
                    el.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:text}));
                  }
                }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                return 'FILLED';
              } catch(e) { return 'ERROR:' + String(e); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val result = decodeJsString(raw)
            if (result == "FILLED") {
                promptFilled = true
                onStatus("AI提示词已写入，等待发送…")
                handler.postDelayed({ clickSendWhenReady() }, 250L)
            } else {
                requireManual("自动写入 ChatGPT 输入框失败：$result")
            }
        }
    }

    private fun clickSendWhenReady() {
        if (promptSent || finished || manualRequested) return
        sendAttempts++
        val script = """
            (function(){
              try {
                const btn = document.querySelector('button[data-testid="send-button"]') ||
                  document.querySelector('button[aria-label="Send prompt"]') ||
                  document.querySelector('button[aria-label="Send"]') ||
                  document.querySelector('button[type="submit"]');
                if (!btn) return JSON.stringify({sent:false,reason:'NO_BUTTON'});
                if (!!btn.disabled || btn.getAttribute('aria-disabled') === 'true') return JSON.stringify({sent:false,reason:'DISABLED'});
                btn.click();
                return JSON.stringify({sent:true,url:location.href});
              } catch(e) { return JSON.stringify({sent:false,reason:String(e)}); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val state = parseJsJson(raw)
            if (state.optBoolean("sent", false)) {
                promptSent = true
                onStatus("AI已发送，正在后台等待回复…")
                handler.postDelayed({ pollAssistantAnswer() }, 700L)
            } else if (sendAttempts < MAX_SEND_ATTEMPTS) {
                handler.postDelayed({ clickSendWhenReady() }, 400L)
            } else {
                requireManual("无法自动点击 ChatGPT 发送按钮")
            }
        }
    }

    private fun pollAssistantAnswer() {
        if (finished || !promptSent || manualRequested) return
        answerPollAttempts++
        val script = """
            (function(){
              try {
                const assistants = Array.from(document.querySelectorAll('[data-message-author-role="assistant"]'));
                const last = assistants.length ? assistants[assistants.length - 1] : null;
                const content = last ? (last.querySelector('.markdown') || last) : null;
                const text = content ? (content.innerText || content.textContent || '').trim() : '';
                const generating = !!(
                  document.querySelector('button[data-testid="stop-button"]') ||
                  document.querySelector('button[aria-label="Stop streaming"]') ||
                  document.querySelector('button[aria-label="Stop generating"]')
                );
                return JSON.stringify({assistantCount:assistants.length,text:text,generating:generating,url:location.href});
              } catch(e) { return JSON.stringify({assistantCount:0,text:'',generating:false,error:String(e),url:location.href}); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            if (finished || manualRequested) return@evaluateJavascript
            val state = parseJsJson(raw)
            val count = state.optInt("assistantCount", 0)
            val text = state.optString("text").trim()
            val generating = state.optBoolean("generating", false)
            val url = state.optString("url")
            if (isConversationUrl(url)) settingsRepo.chatGptConversationUrl = url
            val isNew = count > baselineAssistantCount || (text.isNotBlank() && text != baselineLastAssistantText)
            if (isNew && text.isNotBlank()) {
                stableAnswerPolls = if (!generating && text == lastObservedAnswer) stableAnswerPolls + 1 else 0
                lastObservedAnswer = text
                onStatus(if (generating) "AI正在生成… ${text.length}字" else "AI回复已完成，确认内容稳定…")
                if (!generating && stableAnswerPolls >= REQUIRED_STABLE_POLLS) {
                    complete(text)
                    return@evaluateJavascript
                }
            }
            if (answerPollAttempts < MAX_ANSWER_POLLS) {
                handler.postDelayed({ pollAssistantAnswer() }, ANSWER_POLL_MS)
            } else {
                requireManual("等待 ChatGPT 回复超时")
            }
        }
    }

    private fun complete(answer: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        val prompt = pendingPrompt
        pendingPrompt = ""
        onCompleted(prompt, answer)
    }

    private fun requireManual(reason: String) {
        if (finished || manualRequested) return
        manualRequested = true
        handler.removeCallbacksAndMessages(null)
        onRequiresManual(pendingPrompt, reason)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    private fun parseJsJson(raw: String?): JSONObject =
        runCatching { JSONObject(decodeJsString(raw)) }.getOrElse { JSONObject() }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrElse { raw.trim('"') }
    }

    private fun isConversationUrl(url: String): Boolean =
        url.startsWith("https://chatgpt.com/c/") ||
            (url.startsWith("https://chatgpt.com/g/", ignoreCase = true) && url.contains("/c/"))

    private fun looksLikeLogin(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("auth.openai.com") || u.contains("/auth/login") || u.contains("/login")
    }

    companion object {
        private const val MAX_COMPOSER_ATTEMPTS = 80
        private const val MAX_SEND_ATTEMPTS = 30
        private const val MAX_ANSWER_POLLS = 300
        private const val ANSWER_POLL_MS = 800L
        private const val REQUIRED_STABLE_POLLS = 2
    }
}
