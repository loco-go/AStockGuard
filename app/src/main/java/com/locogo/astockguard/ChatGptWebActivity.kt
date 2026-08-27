package com.locogo.astockguard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.locogo.astockguard.databinding.ActivityChatgptWebBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatGPT Web automation host.
 *
 * Design goals:
 * 1. The user signs in manually in the embedded WebView once. WebView/CookieManager keeps the session.
 * 2. Later prompts are injected into the normal ChatGPT page and the normal Send button is clicked.
 * 3. The app never calls ChatGPT private backend endpoints with copied session tokens.
 * 4. The latest assistant message is read from the rendered page and returned to MainActivity automatically.
 * 5. If login/DOM changes break automation, the WebView stays visible so the user can troubleshoot manually.
 *
 * We intentionally use WebView.evaluateJavascript callbacks instead of addJavascriptInterface because the
 * page is remote web content and addJavascriptInterface would expose native methods to page/iframe JS.
 */
class ChatGptWebActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatgptWebBinding
    private lateinit var settingsRepo: SettingsRepository
    private val handler = Handler(Looper.getMainLooper())

    private var pendingPrompt: String = ""
    private var composerAttempts = 0
    private var sendAttempts = 0
    private var answerPollAttempts = 0
    private var baselineAssistantCount = 0
    private var baselineLastAssistantText = ""
    private var lastObservedAnswer = ""
    private var stableAnswerPolls = 0
    private var automationStarted = false
    private var promptFilled = false
    private var promptSent = false
    private var resultReturned = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatgptWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        settingsRepo = SettingsRepository(this)
        pendingPrompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()

        val newConversation = intent.getBooleanExtra(EXTRA_NEW_CONVERSATION, false)
        if (newConversation) settingsRepo.chatGptConversationUrl = ""

        configureWebView()
        configureButtons()

        if (savedInstanceState == null) {
            val savedUrl = settingsRepo.chatGptConversationUrl
                .takeIf { !newConversation && isChatGptConversationUrl(it) }
            binding.webView.loadUrl(savedUrl ?: CHATGPT_HOME)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val web = binding.webView
        web.settings.apply {
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
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
            flush()
        }

        WebView.setWebContentsDebuggingEnabled(true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean = super.onJsAlert(view, url, message, result)
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.tvWebStatus.text = "加载中：${url.orEmpty()}\nWebView=${webViewVersion()}"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                val u = url.orEmpty()
                if (isChatGptConversationUrl(u)) settingsRepo.chatGptConversationUrl = u

                if (u.contains("chatgpt.com")) {
                    binding.tvWebStatus.text = if (pendingPrompt.isBlank()) {
                        "ChatGPT Web 已加载。登录状态由 WebView 保存。"
                    } else {
                        "ChatGPT Web 已加载，正在等待输入框并自动发送…"
                    }
                    if (pendingPrompt.isNotBlank() && !promptSent) startAutomation()
                } else {
                    binding.tvWebStatus.text = "页面已加载：$u\n如正在登录，请完成登录后等待自动继续。"
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    binding.tvWebStatus.text =
                        "WebView 主页面错误：${error?.errorCode} ${error?.description}\nWebView=${webViewVersion()}\n可手动排查后点‘重新自动发送’。"
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    binding.tvWebStatus.text =
                        "WebView HTTP ${errorResponse?.statusCode}: ${errorResponse?.reasonPhrase.orEmpty()}\n${request.url}\nWebView=${webViewVersion()}\n可手动排查后点‘重新自动发送’。"
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return if (uri.scheme == "http" || uri.scheme == "https") {
                    false
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    true
                }
            }
        }
    }

    private fun configureButtons() {
        binding.btnReload.setOnClickListener { binding.webView.reload() }
        binding.btnFillPrompt.setOnClickListener { restartAutomation() }
        binding.btnReadAnswer.setOnClickListener { readLastAssistantAnswer(manual = true) }
        binding.btnNewChat.setOnClickListener { newConversationAndRetry() }
        binding.btnDiagnostics.setOnClickListener { runDiagnostics() }
        binding.btnOpenBrowser.setOnClickListener { openInSystemBrowser() }
        binding.btnClearWebSession.setOnClickListener { clearWebSession() }
        binding.btnBackToApp.setOnClickListener { finish() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    /** Start/restart the local DOM automation. Login remains manual if required. */
    private fun startAutomation() {
        if (pendingPrompt.isBlank() || promptSent || resultReturned) return
        if (automationStarted) return
        automationStarted = true
        composerAttempts = 0
        sendAttempts = 0
        answerPollAttempts = 0
        stableAnswerPolls = 0
        checkComposerAndSend()
    }

    private fun restartAutomation() {
        handler.removeCallbacksAndMessages(null)
        automationStarted = false
        promptFilled = false
        promptSent = false
        resultReturned = false
        baselineAssistantCount = 0
        baselineLastAssistantText = ""
        lastObservedAnswer = ""
        startAutomation()
    }

    private fun checkComposerAndSend() {
        if (resultReturned || promptSent) return
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
                  assistantCount: assistants.length,
                  lastAssistantText: body ? (body.innerText || body.textContent || '').trim() : ''
                });
              } catch(e) {
                return JSON.stringify({ready:false,error:String(e),url:location.href});
              }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { raw ->
            val state = parseJsJson(raw)
            val ready = state.optBoolean("ready", false)
            val url = state.optString("url")
            if (isChatGptConversationUrl(url)) settingsRepo.chatGptConversationUrl = url

            if (ready) {
                baselineAssistantCount = state.optInt("assistantCount", 0)
                baselineLastAssistantText = state.optString("lastAssistantText")
                fillPrompt()
                return@evaluateJavascript
            }

            if (composerAttempts < MAX_COMPOSER_ATTEMPTS) {
                binding.tvWebStatus.text =
                    "等待 ChatGPT 输入框… ${composerAttempts}/${MAX_COMPOSER_ATTEMPTS}\n" +
                    "如果当前显示登录页，请手动完成登录；登录完成后会自动继续。"
                handler.postDelayed({ checkComposerAndSend() }, 1000L)
            } else {
                automationStarted = false
                binding.tvWebStatus.text =
                    "长时间未检测到输入框。请在当前 WebView 中手动检查登录/页面状态，完成后点‘重新自动发送’。"
            }
        }
    }

    private fun fillPrompt() {
        if (promptFilled || promptSent || resultReturned) return
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
                  selection.removeAllRanges();
                  selection.addRange(range);
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
              } catch (e) {
                return 'ERROR:' + String(e);
              }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { raw ->
            val result = decodeJsString(raw)
            if (result == "FILLED") {
                promptFilled = true
                binding.tvWebStatus.text = "提示词已写入，等待发送按钮可用…"
                handler.postDelayed({ clickSendWhenReady() }, 300L)
            } else {
                automationStarted = false
                binding.tvWebStatus.text = "自动写入失败：$result\n可手动排查后点‘重新自动发送’。"
            }
        }
    }

    private fun clickSendWhenReady() {
        if (promptSent || resultReturned) return
        sendAttempts++
        val script = """
            (function(){
              try {
                const btn = document.querySelector('button[data-testid="send-button"]') ||
                  document.querySelector('button[aria-label="Send prompt"]') ||
                  document.querySelector('button[aria-label="Send"]') ||
                  document.querySelector('button[type="submit"]');
                if (!btn) return JSON.stringify({sent:false,reason:'NO_BUTTON'});
                const disabled = !!btn.disabled || btn.getAttribute('aria-disabled') === 'true';
                if (disabled) return JSON.stringify({sent:false,reason:'DISABLED'});
                btn.click();
                return JSON.stringify({sent:true,url:location.href});
              } catch(e) {
                return JSON.stringify({sent:false,reason:String(e)});
              }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { raw ->
            val state = parseJsJson(raw)
            if (state.optBoolean("sent", false)) {
                promptSent = true
                binding.tvWebStatus.text = "已自动发送，等待 ChatGPT 回复并自动回传到 App…"
                handler.postDelayed({ pollAssistantAnswer() }, 700L)
                return@evaluateJavascript
            }

            if (sendAttempts < MAX_SEND_ATTEMPTS) {
                binding.tvWebStatus.text = "等待发送按钮可用… ${sendAttempts}/${MAX_SEND_ATTEMPTS}"
                handler.postDelayed({ clickSendWhenReady() }, 400L)
            } else {
                automationStarted = false
                binding.tvWebStatus.text =
                    "输入框已填入，但未能自动点击发送。请检查网页状态；可手动发送后点‘手动读取回复’，或点‘重新自动发送’。"
            }
        }
    }

    private fun pollAssistantAnswer() {
        if (resultReturned || !promptSent) return
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
                return JSON.stringify({
                  assistantCount: assistants.length,
                  text: text,
                  generating: generating,
                  url: location.href,
                  hasComposer: !!(document.querySelector('#prompt-textarea') || document.querySelector('[contenteditable="true"][role="textbox"]'))
                });
              } catch(e) {
                return JSON.stringify({assistantCount:0,text:'',generating:false,error:String(e),url:location.href});
              }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { raw ->
            val state = parseJsJson(raw)
            val count = state.optInt("assistantCount", 0)
            val text = state.optString("text").trim()
            val generating = state.optBoolean("generating", false)
            val url = state.optString("url")

            if (isChatGptConversationUrl(url)) settingsRepo.chatGptConversationUrl = url

            val isNewAssistant = count > baselineAssistantCount ||
                (text.isNotBlank() && text != baselineLastAssistantText)

            if (isNewAssistant && text.isNotBlank()) {
                if (!generating && text == lastObservedAnswer) {
                    stableAnswerPolls++
                } else {
                    stableAnswerPolls = 0
                }
                lastObservedAnswer = text

                binding.tvWebStatus.text = if (generating) {
                    "ChatGPT 正在回复… 已读取 ${text.length} 字符"
                } else {
                    "回复已出现，等待内容稳定… ${stableAnswerPolls + 1}/$REQUIRED_STABLE_POLLS"
                }

                if (!generating && stableAnswerPolls >= REQUIRED_STABLE_POLLS) {
                    completeWithAnswer(text, url)
                    return@evaluateJavascript
                }
            } else {
                binding.tvWebStatus.text = "已发送，等待新的助手回复… ${answerPollAttempts}s"
            }

            if (answerPollAttempts < MAX_ANSWER_POLLS) {
                handler.postDelayed({ pollAssistantAnswer() }, ANSWER_POLL_MS)
            } else {
                automationStarted = false
                binding.tvWebStatus.text =
                    "等待回复超时。网页仍保留当前会话；如果回复已完成，可点‘手动读取回复’。"
            }
        }
    }

    private fun readLastAssistantAnswer(manual: Boolean) {
        val script = """
            (function() {
              const nodes = Array.from(document.querySelectorAll('[data-message-author-role="assistant"]'));
              const last = nodes.length ? nodes[nodes.length - 1] : null;
              const body = last ? (last.querySelector('.markdown') || last) : null;
              return JSON.stringify({
                text: body ? (body.innerText || body.textContent || '').trim() : '',
                url: location.href
              });
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { raw ->
            val state = parseJsJson(raw)
            val answer = state.optString("text").trim()
            val url = state.optString("url")
            if (answer.isBlank()) {
                if (manual) toast("没有读取到助手回复；请确认网页已经完成回复")
                return@evaluateJavascript
            }
            completeWithAnswer(answer, url)
        }
    }

    private fun completeWithAnswer(answer: String, url: String) {
        if (resultReturned) return
        resultReturned = true
        handler.removeCallbacksAndMessages(null)
        if (isChatGptConversationUrl(url)) settingsRepo.chatGptConversationUrl = url
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT, answer)
                .putExtra(EXTRA_CONVERSATION_URL, settingsRepo.chatGptConversationUrl)
        )
        finish()
    }

    private fun newConversationAndRetry() {
        handler.removeCallbacksAndMessages(null)
        settingsRepo.chatGptConversationUrl = ""
        automationStarted = false
        promptFilled = false
        promptSent = false
        resultReturned = false
        baselineAssistantCount = 0
        baselineLastAssistantText = ""
        lastObservedAnswer = ""
        binding.tvWebStatus.text = "正在创建新对话…"
        binding.webView.loadUrl(CHATGPT_HOME)
    }

    private fun runDiagnostics() {
        val script = """
            (function(){
              return JSON.stringify({
                url: location.href,
                title: document.title,
                promptTextarea: document.querySelectorAll('#prompt-textarea').length,
                roleTextbox: document.querySelectorAll('[contenteditable="true"][role="textbox"]').length,
                sendButton: document.querySelectorAll('button[data-testid="send-button"]').length,
                stopButton: document.querySelectorAll('button[data-testid="stop-button"]').length,
                userMessages: document.querySelectorAll('[data-message-author-role="user"]').length,
                assistantMessages: document.querySelectorAll('[data-message-author-role="assistant"]').length
              });
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { raw ->
            val json = decodeJsString(raw)
            binding.tvWebStatus.text = "DOM诊断：\n$json\nWebView=${webViewVersion()}"
        }
    }

    private fun openInSystemBrowser() {
        if (pendingPrompt.isNotBlank()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AStockGuard Prompt", pendingPrompt))
            toast("提示词已复制；系统浏览器打开后可作为应急方式使用")
        }
        val url = settingsRepo.chatGptConversationUrl.takeIf(::isChatGptConversationUrl) ?: CHATGPT_HOME
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("无法打开系统浏览器：${it.message}") }
    }

    private fun webViewVersion(): String {
        val info = WebView.getCurrentWebViewPackage()
        return if (info == null) "unknown" else "${info.packageName} ${info.versionName}"
    }

    private fun clearWebSession() {
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            binding.webView.clearCache(true)
            binding.webView.clearHistory()
            settingsRepo.chatGptConversationUrl = ""
            automationStarted = false
            promptFilled = false
            promptSent = false
            resultReturned = false
            binding.webView.loadUrl(CHATGPT_HOME)
            toast("WebView 登录会话和当前对话地址已清除")
        }
    }

    private fun parseJsJson(raw: String?): JSONObject {
        val decoded = decodeJsString(raw)
        return runCatching { JSONObject(decoded) }.getOrElse { JSONObject() }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrElse { raw.trim('"') }
    }

    private fun isChatGptConversationUrl(url: String): Boolean =
        url.startsWith("https://chatgpt.com/c/") || url.startsWith("https://chatgpt.com/g/", ignoreCase = true) && url.contains("/c/")

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_RESULT = "extra_result"
        const val EXTRA_NEW_CONVERSATION = "extra_new_conversation"
        const val EXTRA_CONVERSATION_URL = "extra_conversation_url"
        const val CHATGPT_HOME = "https://chatgpt.com/"

        private const val MAX_COMPOSER_ATTEMPTS = 120
        private const val MAX_SEND_ATTEMPTS = 30
        private const val MAX_ANSWER_POLLS = 300
        private const val ANSWER_POLL_MS = 800L
        private const val REQUIRED_STABLE_POLLS = 2
    }
}
