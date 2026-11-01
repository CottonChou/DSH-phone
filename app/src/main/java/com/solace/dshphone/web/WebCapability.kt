package com.solace.dshphone.web

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.solace.dshphone.core.action.ActionResult
import com.solace.dshphone.core.safety.DeviceAction
import com.solace.dshphone.core.tool.AgentTool
import com.solace.dshphone.core.tool.Args
import com.solace.dshphone.core.tool.CapabilityApi
import com.solace.dshphone.core.action.Risk
import com.solace.dshphone.core.tool.ToolContext
import com.solace.dshphone.core.tool.ToolResult
import com.solace.dshphone.phone.describe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/**
 * A private, desktop-mode browser the agent drives page by page: navigate,
 * read the extracted text, click by link or button text, and type into fields.
 * Login state lives in this profile only; risk-control pages are handed back
 * to the user instead of being fought.
 */
class WebCapability(private val webViewProvider: () -> WebView?) : CapabilityApi {

    override val id = "web"

    override fun promptSection() = """
        ## 网页能力
        内置浏览器以电脑版方式打开网站。web_navigate 打开地址，web_read_page 读正文，web_click 按文字点击，web_input 填输入框。
        每次连续访问之间至少等 2 秒；遇到验证码或风控页时不要绕过，用 end_not_done（subtype=captcha_incomplete）或 ask_user 交给用户。
    """.trimIndent()

    override fun tools(): List<AgentTool> = listOf(
        object : AgentTool("web_navigate", "打开一个网址（入口工具，调用后展开网页能力）", Risk.READ, id,
            entry = "web", parametersJson = schema("url" to "要打开的地址", required = listOf("url"))) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val wv = webViewProvider() ?: return ToolResult.fail("浏览器不可用")
                var url = Args.str(a, "url")
                if (!url.startsWith("http")) url = "https://$url"
                wv.loadUrlAndWait(url)
                delay(2_000) // pacing between consecutive visits
                val title = wv.title ?: ""
                return ToolResult.ok("已打开 $url（$title）。用 web_read_page 阅读内容", opensGroup = "web")
            }
        },
        object : AgentTool("web_read_page", "读取当前页面的正文", Risk.READ, id,
            parametersJson = schema("max_chars" to "最多读取的字符数，默认 3000")) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val wv = webViewProvider() ?: return ToolResult.fail("浏览器不可用")
                val max = Args.int(a, "max_chars", 3_000)
                val text = wv.extractText()
                if (RiskControlDetector.looksLikeRiskPage(text)) {
                    return ToolResult.ok("页面疑似风控/验证页：${text.take(200)}\n不要尝试绕过；请让用户接管处理")
                }
                ctx.ledger.add(
                    com.solace.dshphone.core.observe.Observation(
                        id = "", kind = com.solace.dshphone.core.observe.ObsKind.READ,
                        sourceCapability = id,
                        place = com.solace.dshphone.core.observe.Place(
                            com.solace.dshphone.core.observe.Place.Kind.PAGE, wv.url ?: ""),
                        title = wv.title ?: "", text = com.solace.dshphone.core.observe.Observation.fence(text.take(max)),
                        step = ctx.step, timestampMs = System.currentTimeMillis(),
                    )
                )
                return ToolResult.ok("页面：${wv.title}（${wv.url}）\n$text")
            }
        },
        object : AgentTool("web_click", "按文字点击页面上的链接或按钮", Risk.WRITE, id,
            parametersJson = schema("target" to "链接/按钮文字", required = listOf("target"))) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val wv = webViewProvider() ?: return ToolResult.fail("浏览器不可用")
                val target = Args.str(a, "target")
                val result = ctx.executor.execute(
                    DeviceAction.Tap, wv.url ?: "web", target, wv.extractText(800),
                )
                return when (result) {
                    is ActionResult.Ok -> ToolResult.ok("已点击「$target」")
                    is ActionResult.Blocked -> ToolResult.fail(result.block.describe())
                    is ActionResult.Failed -> ToolResult.fail("点击失败：${result.error}")
                }
            }
        },
        object : AgentTool("web_input", "按提示文字向页面输入框填内容", Risk.WRITE, id,
            parametersJson = schema("field" to "输入框提示/占位文字", "text" to "内容", required = listOf("field", "text"))) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val wv = webViewProvider() ?: return ToolResult.fail("浏览器不可用")
                val field = Args.str(a, "field").replace("\"", "")
                val text = Args.str(a, "text").replace("\"", "")
                val ok = wv.runJs("document.querySelectorAll('input,textarea').forEach(i=>{const k=(i.placeholder||i.name||i.id||i.getAttribute('aria-label')||'');if(k.includes('$field')){i.value='$text';i.dispatchEvent(new Event('input',{bubbles:true}))}});")
                return if (ok) ToolResult.ok("已尝试填入") else ToolResult.fail("填入失败")
            }
        },
    )
}

private fun schema(vararg fields: Pair<String, String>, required: List<String> = emptyList()): String =
    kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
        put("properties", kotlinx.serialization.json.buildJsonObject {
            for ((name, desc) in fields) {
                put(name, kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive(desc))
                })
            }
        })
        if (required.isNotEmpty()) put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }.toString()

@SuppressLint("SetJavaScriptEnabled")
fun buildAgentWebView(context: android.content.Context): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false
        }
    }
}

/** Load a URL and wait until the page stops loading (bounded). */
fun WebView.loadUrlAndWait(url: String, timeoutMs: Long = 15_000) {
    val done = CompletableDeferred<Unit>()
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            done.complete(Unit)
        }
    }
    post { loadUrl(url) }
    runCatching { kotlinx.coroutines.runBlocking { withTimeout(timeoutMs) { done.await() } } }
}

fun WebView.extractText(maxChars: Int = 4_000): String {
    val box = AtomicReference("")
    val latch = java.util.concurrent.CountDownLatch(1)
    post {
        evaluateJavascript(
            "(function(){const t=document.body?document.body.innerText:'';return t;})()",
        ) { v -> box.set(v?.trim('"')?.unescapeJson() ?: ""); latch.countDown() }
    }
    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    return box.get().take(maxChars)
}

fun WebView.runJs(js: String): Boolean {
    val latch = java.util.concurrent.CountDownLatch(1)
    return try {
        post {
            evaluateJavascript(js) { latch.countDown() }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    } catch (e: Exception) {
        false
    }
}

private fun String.unescapeJson(): String =
    replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")

/** Cheap heuristic; the agent is told to hand risk-control pages back, never to fight them. */
object RiskControlDetector {
    private val hints = listOf("验证码", "滑动验证", "安全验证", "访问过于频繁", "captcha", "unusual traffic", "robot check")
    fun looksLikeRiskPage(text: String): Boolean {
        val t = text.take(1_000).lowercase()
        return hints.any { t.contains(it.lowercase()) }
    }
}
