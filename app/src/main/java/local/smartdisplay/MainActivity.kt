package local.smartdisplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/**
 * 画面(assets/index.html)を WebView で表示し、SwitchBot API の呼び出し・設定保存・
 * タイマーのアラーム音は Kotlin 側で行う。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var prefs: Prefs
    private val api = SwitchBotApi()
    private val weatherApi = WeatherApi()
    private lateinit var googleApi: GoogleApi
    private lateinit var pcClient: PcClient
    private val pool = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private val stopAlarmRunnable = Runnable { stopAlarm() }
    private var promptedSettings = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pcClient = PcClient(prefs)
        googleApi = GoogleApi(prefs)

        // 壁掛け用: 画面を常時点灯
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        web = WebView(this)
        web.setBackgroundColor(0xFF161616.toInt())
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isLongClickable = false
        web.isHapticFeedbackEnabled = false
        web.setOnLongClickListener { true }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100 // 端末の文字サイズ設定でレイアウトが崩れないように固定
            setSupportZoom(false)
            builtInZoomControls = false
        }

        // assets を https://appassets.androidplatform.net/assets/ として配信。
        // それ以外のホストへの通信・遷移はすべて遮断する（画面からは外部に出られない）。
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
                    return loader.shouldInterceptRequest(url)
                }
                return WebResourceResponse(
                    "text/plain", "utf-8", 403, "Blocked", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html")
    }

    override fun onResume() {
        super.onResume()
        // 設定画面から戻ったら、画面側に設定の再読み込みを依頼
        web.evaluateJavascript("window.onSettingsChanged && window.onSettingsChanged()", null)
        // 初回起動（トークン未設定）は設定画面を開く
        if (!prefs.configured && !promptedSettings) {
            promptedSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onPause() {
        super.onPause()
        setVis(false)   // 画面が前面にない間は棒グラフの受信を止める（戻ると画面側が再開を依頼する）
    }

    override fun onDestroy() {
        setVis(false)
        stopAlarm()
        pool.shutdown()
        super.onDestroy()
    }

    // ---------------- タイマーの通知（振動のみ。音は鳴らさない） ----------------

    @Suppress("DEPRECATION")
    private fun startAlarm() {
        handler.removeCallbacks(stopAlarmRunnable)
        if (!Vib.start(this)) return
        handler.postDelayed(stopAlarmRunnable, 60_000) // 振動しっぱなし防止: 60秒で自動停止
    }

    @Suppress("DEPRECATION")
    private fun stopAlarm() {
        handler.removeCallbacks(stopAlarmRunnable)
        Vib.stop(this)
    }

    // ---------------- PC の棒グラフ（Cava）の受信 ----------------

    @Volatile private var visGen = 0
    @Volatile private var visConn: HttpURLConnection? = null
    private val visPending = AtomicBoolean(false)

    /** on=true で受信開始。切れたら2秒ごとにつなぎ直す。off で止める */
    private fun setVis(on: Boolean) {
        val gen = ++visGen
        try { visConn?.disconnect() } catch (e: Exception) {}
        if (!on || prefs.pcToken.isBlank()) return
        Thread {
            while (visGen == gen) {
                try {
                    pcClient.stream({ visGen == gen }, { visConn = it }) { line -> pushVis(line) }
                } catch (e: Exception) { /* 接続できない・切れた → 待ってから再試行 */ }
                if (visGen != gen) break
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true }.start()
    }

    private val visRegex = Regex("^[0-9;]{1,1200}$")

    private fun pushVis(line: String) {
        if (!visRegex.matches(line)) return
        if (!visPending.compareAndSet(false, true)) return   // 画面が追いついていないフレームは捨てる
        runOnUiThread {
            web.evaluateJavascript("window.onVis&&window.onVis('$line')") { visPending.set(false) }
        }
    }

    // ---------------- 画面(JS)から呼ばれる窓口 ----------------

    inner class Bridge {

        /** target: "ac" または "light"。結果は window.onNativeResult(reqId, ok, message) で返す */
        @JavascriptInterface
        fun send(reqId: String, target: String, command: String, parameter: String, commandType: String) {
            pool.execute {
                val deviceId = if (target == "ac") prefs.acDeviceId else prefs.lightDeviceId
                val r = api.sendCommand(prefs.token, prefs.secret, deviceId, command, parameter, commandType)
                val js = "window.onNativeResult(${JSONObject.quote(reqId)},${r.ok},${JSONObject.quote(r.message)})"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }

        /** 画面に必要な非機密の設定だけを返す（トークンは返さない） */
        @JavascriptInterface
        fun getConfig(): String = JSONObject()
            .put("configured", prefs.configured)
            .put("nightCmd", prefs.nightCmd)
            .put("allCmd", prefs.allCmd)
            .put("pulseMs", prefs.pulseMs)
            .put("idleSec", prefs.idleSec)
            .put("lat", prefs.wxLat)
            .put("lon", prefs.wxLon)
            .put("model", prefs.wxModel)
            .put("pcConfigured", prefs.pcToken.isNotBlank())
            .put("visSmooth", prefs.visSmooth)
            .put("visPeak", prefs.visPeak)
            .put("visLayout", prefs.visLayout)
            .put("googleConfigured", prefs.googleConfigured)
            .put("taskUndated", prefs.taskUndated)
            .toString()

        /** 天気の取得。結果は window.onWeatherResult(reqId, ok, message, body) で返す */
        @JavascriptInterface
        fun fetchWeather(reqId: String) {
            pool.execute {
                val r = weatherApi.fetch(prefs.wxLat, prefs.wxLon, prefs.wxModel)
                val js = "window.onWeatherResult(${JSONObject.quote(reqId)},${r.ok},${JSONObject.quote(r.message)},${JSONObject.quote(r.body ?: "")})"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }

        /** Google カレンダー / ToDo の取得。timeMin・timeMax は RFC3339(UTC)。結果は window.onGoogleResult(reqId, ok, message, body) */
        @JavascriptInterface
        fun fetchGoogle(reqId: String, timeMin: String, timeMax: String) {
            val re = Regex("^\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z$")
            if (!re.matches(timeMin) || !re.matches(timeMax)) return
            pool.execute {
                val r = googleApi.fetch(timeMin, timeMax)
                val js = "window.onGoogleResult(${JSONObject.quote(reqId)},${r.ok},${JSONObject.quote(r.message)},${JSONObject.quote(r.body ?: "")})"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }

        /** PC 側プログラムへの要求。path は /status /media /volume のみ。結果は window.onPcResult(reqId, ok, message, body) */
        @JavascriptInterface
        fun pcRequest(reqId: String, method: String, path: String, body: String) {
            pool.execute {
                val r = pcClient.request(if (method == "POST") "POST" else "GET", path, body)
                val js = "window.onPcResult(${JSONObject.quote(reqId)},${r.ok},${JSONObject.quote(r.message)},${JSONObject.quote(r.body ?: "")})"
                runOnUiThread { web.evaluateJavascript(js, null) }
            }
        }

        /** 棒グラフ（VIS）の受信の開始・停止 */
        @JavascriptInterface
        fun pcVis(on: Boolean) {
            runOnUiThread { setVis(on) }
        }

        @JavascriptInterface
        fun loadState(): String = prefs.state

        @JavascriptInterface
        fun saveState(json: String) {
            prefs.state = json
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        @JavascriptInterface
        fun alarm(on: Boolean) {
            runOnUiThread { if (on) startAlarm() else stopAlarm() }
        }
    }
}
