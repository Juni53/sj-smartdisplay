package local.smartdisplay

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/** トークン・デバイスIDなどの設定画面。標準部品だけで組んだ簡素な画面。 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val pool = Executors.newSingleThreadExecutor()

    private lateinit var tokenField: EditText
    private lateinit var secretField: EditText
    private lateinit var acField: EditText
    private lateinit var lightField: EditText
    private lateinit var nightField: EditText
    private lateinit var allField: EditText
    private lateinit var pulseField: EditText
    private lateinit var idleField: EditText
    private lateinit var latField: EditText
    private lateinit var lonField: EditText
    private lateinit var modelField: EditText
    private lateinit var pcPortField: EditText
    private lateinit var pcTokenField: EditText
    private lateinit var visSmoothBox: CheckBox
    private lateinit var visPeakBox: CheckBox
    private lateinit var layout1: RadioButton
    private lateinit var layout2: RadioButton
    private lateinit var gIdField: EditText
    private lateinit var gSecretField: EditText
    private lateinit var gRefreshField: EditText
    private lateinit var taskUndatedBox: CheckBox
    private lateinit var resultView: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(0xFF9A9A9A.toInt())
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        }

        fun field(hint: String, value: String, password: Boolean = false, number: Boolean = false, decimal: Boolean = false) =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                setTextColor(0xFFECECEC.toInt())
                setHintTextColor(0xFF777777.toInt())
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    number -> InputType.TYPE_CLASS_NUMBER
                    decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
            }

        col.addView(TextView(this).apply {
            text = "設定"
            setTextColor(0xFFECECEC.toInt())
            textSize = 20f
        })

        col.addView(label("SwitchBot トークン"))
        tokenField = field(if (prefs.token.isNotBlank()) "設定済み（変更する場合のみ入力）" else "トークンを貼り付け", "", password = true)
        col.addView(tokenField)

        col.addView(label("SwitchBot シークレット"))
        secretField = field(if (prefs.secret.isNotBlank()) "設定済み（変更する場合のみ入力）" else "シークレットを貼り付け", "", password = true)
        col.addView(secretField)

        col.addView(label("エアコンのデバイスID"))
        acField = field("デバイスID", prefs.acDeviceId)
        col.addView(acField)

        col.addView(label("照明のデバイスID"))
        lightField = field("デバイスID", prefs.lightDeviceId)
        col.addView(lightField)

        col.addView(label("照明カスタムボタン名: 常夜灯"))
        nightField = field("常夜灯", prefs.nightCmd)
        col.addView(nightField)

        col.addView(label("照明カスタムボタン名: 全灯"))
        allField = field("全灯", prefs.allCmd)
        col.addView(allField)

        col.addView(label("明るさ長押しの送信間隔 (ms / 400〜5000)"))
        pulseField = field("600", prefs.pulseMs.toString(), number = true)
        col.addView(pulseField)

        col.addView(label("待機画面になるまでの無操作時間 (秒 / 10〜3600、0で自動では待機しない)"))
        idleField = field("60", prefs.idleSec.toString(), number = true)
        col.addView(idleField)

        col.addView(label("天気: 緯度（-90〜90）"))
        latField = field("35.6895", prefs.wxLat, decimal = true)
        col.addView(latField)

        col.addView(label("天気: 経度（-180〜180）"))
        lonField = field("139.6917", prefs.wxLon, decimal = true)
        col.addView(lonField)

        col.addView(label("天気: モデル（Open-Meteo の models。例 jma_msm。空欄でお任せ）"))
        modelField = field("jma_msm", prefs.wxModel)
        col.addView(modelField)

        col.addView(label("PC連携: ポート番号（PC側プログラムと同じ値。既定 8765）"))
        pcPortField = field("8765", prefs.pcPort.toString(), number = true)
        col.addView(pcPortField)

        col.addView(label("PC連携: トークン（PC側プログラムが表示する値。空欄のままなら連携しない）"))
        pcTokenField = field(if (prefs.pcToken.isNotBlank()) "設定済み（変更する場合のみ入力）" else "トークンを貼り付け", "", password = true)
        col.addView(pcTokenField)

        val pcTestBtn = Button(this).apply {
            text = "PCの接続テスト"
            setOnClickListener { runPcTest() }
        }
        col.addView(pcTestBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        fun checkBox(text: String, on: Boolean) = CheckBox(this).apply {
            this.text = text
            isChecked = on
            setTextColor(0xFFECECEC.toInt())
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(label("ビジュアライザー（VIS）"))
        visSmoothBox = checkBox("補完する（届いた値を画面側で60fpsに滑らかにする）", prefs.visSmooth)
        col.addView(visSmoothBox)
        visPeakBox = checkBox("ピークホールド（各棒の最高点に線を残して、少し待って落とす）", prefs.visPeak)
        col.addView(visPeakBox)

        col.addView(label("VIS待機画面のレイアウト"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fun radio(text: String, id: Int) = RadioButton(this).apply {
            this.text = text
            this.id = id
            setTextColor(0xFFECECEC.toInt())
            textSize = 14f
        }
        layout1 = radio("レイアウト1: 上に時刻・天気、下に棒グラフ", 1001)
        layout2 = radio("レイアウト2: PC待機と同じ左の時計 + 右に棒グラフ", 1002)
        group.addView(layout1)
        group.addView(layout2)
        group.check(if (prefs.visLayout == 2) 1002 else 1001)
        col.addView(group)

        col.addView(label("Google カレンダー / ToDo（概要に表示。読み取り専用。手順は README）"))
        gIdField = field(if (prefs.googleClientId.isNotBlank()) "設定済み（変更する場合のみ入力）" else "クライアントID", "")
        col.addView(gIdField)
        gSecretField = field(if (prefs.googleClientSecret.isNotBlank()) "設定済み（変更する場合のみ入力）" else "クライアントシークレット", "", password = true)
        col.addView(gSecretField)
        gRefreshField = field(if (prefs.googleRefresh.isNotBlank()) "設定済み（変更する場合のみ入力）" else "リフレッシュトークン", "", password = true)
        col.addView(gRefreshField)
        taskUndatedBox = checkBox("期日のないToDoも概要に表示する", prefs.taskUndated)
        col.addView(taskUndatedBox)
        val gImportBtn = Button(this).apply {
            text = "PCから取り込む（google_auth.py の結果）"
            setOnClickListener { runGoogleImport() }
        }
        col.addView(gImportBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val gTestBtn = Button(this).apply {
            text = "Googleの取得テスト"
            setOnClickListener { runGoogleTest() }
        }
        col.addView(gTestBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val vibBtn = Button(this).apply {
            text = "振動テスト（2秒）"
            setOnClickListener {
                if (Vib.start(this@SettingsActivity)) {
                    resultView.text = "振動中…（タイマー終了時と同じ振動です）"
                    postDelayed({ Vib.stop(this@SettingsActivity) }, 2000)
                } else {
                    resultView.text = "✗ この端末では振動を始められませんでした"
                }
            }
        }
        col.addView(vibBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val wxTestBtn = Button(this).apply {
            text = "天気の取得テスト"
            setOnClickListener { runWeatherTest() }
        }
        col.addView(wxTestBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val testBtn = Button(this).apply {
            text = "接続テスト（デバイス一覧を取得）"
            setOnClickListener { runTest() }
        }
        col.addView(testBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        resultView = TextView(this).apply {
            setTextColor(0xFFECECEC.toInt())
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(resultView)

        val saveBtn = Button(this).apply {
            text = "保存して戻る"
            setOnClickListener {
                save()
                finish()
            }
        }
        col.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF161616.toInt())
            addView(col)
        })
    }

    private fun save() {
        // トークン/シークレットは入力があるときだけ上書き（空欄なら今の値を保持）
        tokenField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.token = it }
        secretField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.secret = it }
        prefs.acDeviceId = acField.text.toString().trim()
        prefs.lightDeviceId = lightField.text.toString().trim()
        prefs.nightCmd = nightField.text.toString().trim().ifEmpty { "常夜灯" }
        prefs.allCmd = allField.text.toString().trim().ifEmpty { "全灯" }
        prefs.pulseMs = (pulseField.text.toString().toIntOrNull() ?: 600).coerceIn(400, 5000)
        val idle = idleField.text.toString().toIntOrNull() ?: 60
        prefs.idleSec = if (idle <= 0) 0 else idle.coerceIn(10, 3600)
        // 緯度・経度は範囲外や数値でないときは今の値を保持する
        val bad = mutableListOf<String>()
        val la = latField.text.toString().trim()
        if (la.toDoubleOrNull()?.let { it in -90.0..90.0 } == true) prefs.wxLat = la else bad.add("緯度")
        val lo = lonField.text.toString().trim()
        if (lo.toDoubleOrNull()?.let { it in -180.0..180.0 } == true) prefs.wxLon = lo else bad.add("経度")
        prefs.wxModel = modelField.text.toString().trim()
        prefs.pcPort = (pcPortField.text.toString().toIntOrNull() ?: 8765).coerceIn(1024, 65535)
        pcTokenField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.pcToken = it }
        gIdField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.googleClientId = it }
        gSecretField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.googleClientSecret = it }
        gRefreshField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.googleRefresh = it }
        prefs.taskUndated = taskUndatedBox.isChecked
        prefs.visSmooth = visSmoothBox.isChecked
        prefs.visPeak = visPeakBox.isChecked
        prefs.visLayout = if (layout2.isChecked) 2 else 1
        if (bad.isNotEmpty()) Toast.makeText(this, bad.joinToString("・") + "が正しくないため、元の値のままです", Toast.LENGTH_LONG).show()
    }

    private fun runPcTest() {
        // 入力中の値で試す（まだ保存していなくてもよい）
        val port = (pcPortField.text.toString().toIntOrNull() ?: 8765).coerceIn(1024, 65535)
        val token = pcTokenField.text.toString().trim().ifEmpty { prefs.pcToken }
        resultView.text = "PCに接続中…"
        pool.execute {
            val text = try {
                val c = java.net.URL("http://127.0.0.1:$port/status").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 2000
                c.readTimeout = 4000
                c.setRequestProperty("X-Token", token)
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                when {
                    code == 401 -> "✗ トークンが違います"
                    code !in 200..299 -> "✗ HTTP $code"
                    else -> {
                        val j = JSONObject(body)
                        val m = j.optJSONObject("media")
                        "✓ PCにつながりました\nCPU ${j.opt("cpu")}% / メモリ ${j.opt("mem")}% / GPU ${if (j.isNull("gpu")) "取得できません" else j.opt("gpu").toString() + "%"}\n" +
                            "再生中: ${if (m != null && m.optBoolean("available")) m.optString("title") else "なし（または取得できません）"}\n" +
                            "音量: ${if (j.isNull("volume")) "取得できません（ボタン操作は可）" else j.opt("volume").toString()}\n" +
                            "Cava: ${if (j.optBoolean("vis_ok")) "見つかりました" else "見つかりません（VISは使えません）"}"
                    }
                }
            } catch (e: Exception) {
                "✗ PCに接続できません（PC側プログラムの起動、USB接続、USBデバッグの許可を確認してください）"
            }
            runOnUiThread { resultView.text = text }
        }
    }

    /** PC側プログラムが持つ google_config.json（google_auth.py の結果）を取り込む。入力欄の値は使わず、保存済みのPC設定は入力中の値を優先 */
    private fun runGoogleImport() {
        val port = (pcPortField.text.toString().toIntOrNull() ?: prefs.pcPort).coerceIn(1024, 65535)
        val token = pcTokenField.text.toString().trim().ifEmpty { prefs.pcToken }
        if (token.isEmpty()) { resultView.text = "✗ 先にPC連携のトークンを設定してください"; return }
        resultView.text = "PCから取り込み中…"
        pool.execute {
            val text = try {
                val c = java.net.URL("http://127.0.0.1:$port/google").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 2000
                c.readTimeout = 4000
                c.setRequestProperty("X-Token", token)
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                when {
                    code == 401 -> "✗ PCのトークンが違います"
                    code !in 200..299 -> "✗ " + (try { JSONObject(body).optString("error") } catch (e: Exception) { "" }).ifEmpty { "HTTP $code" }
                    else -> {
                        val j = JSONObject(body)
                        val id = j.optString("client_id"); val sec = j.optString("client_secret"); val rt = j.optString("refresh_token")
                        if (id.isEmpty() || sec.isEmpty() || rt.isEmpty()) "✗ PC側のGoogle設定が不完全です"
                        else {
                            prefs.googleClientId = id; prefs.googleClientSecret = sec; prefs.googleRefresh = rt
                            "✓ 取り込みました。「Googleの取得テスト」で確認してください"
                        }
                    }
                }
            } catch (e: Exception) {
                "✗ PCに接続できません（PC側プログラムの起動とUSB接続を確認してください）"
            }
            runOnUiThread { resultView.text = text }
        }
    }

    private fun runGoogleTest() {
        // 入力欄に新しい値があれば、先に保存して試す
        gIdField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.googleClientId = it }
        gSecretField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.googleClientSecret = it }
        gRefreshField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.googleRefresh = it }
        resultView.text = "Googleから取得中…"
        pool.execute {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val now = System.currentTimeMillis()
            val r = GoogleApi(prefs).fetch(fmt.format(java.util.Date(now)), fmt.format(java.util.Date(now + 2 * 86_400_000L)))
            val text = if (!r.ok) "✗ ${r.message}" else try {
                val j = JSONObject(r.body ?: "")
                "✓ 取得OK（今後2日: 予定 ${j.getJSONArray("events").length()}件 / ToDo ${j.getJSONArray("tasks").length()}件）" +
                    (if (j.optString("errCal").isNotEmpty()) "\n⚠ カレンダー: ${j.optString("errCal")}" else "") +
                    (if (j.optString("errTask").isNotEmpty()) "\n⚠ ToDo: ${j.optString("errTask")}" else "")
            } catch (e: Exception) {
                "✗ 応答を読み取れません"
            }
            runOnUiThread { resultView.text = text }
        }
    }

    private fun runWeatherTest() {
        val la = latField.text.toString().trim()
        val lo = lonField.text.toString().trim()
        val m = modelField.text.toString().trim()
        resultView.text = "天気を取得中…"
        pool.execute {
            val r = WeatherApi().fetch(la, lo, m)
            val text = if (!r.ok) "✗ ${r.message}" else try {
                val h = JSONObject(r.body ?: "").getJSONObject("hourly")
                val n = h.getJSONArray("time").length()
                fun nonNull(key: String): Int {
                    val a = h.optJSONArray(key) ?: return 0
                    var c = 0
                    for (i in 0 until a.length()) if (!a.isNull(i)) c++
                    return c
                }
                val codes = maxOf(nonNull("weathercode"), nonNull("weather_code"))
                "✓ 取得OK（${n}時間分）\n天気コード ${codes}件 / 気温 ${nonNull("temperature_2m")}件 / 降水量 ${nonNull("precipitation")}件 / 風 ${nonNull("wind_speed_10m")}件 / 湿度 ${nonNull("relative_humidity_2m")}件" +
                    if (codes == 0) "\n⚠ このモデルは天気コードを返していません（シンボルが出ません）" else ""
            } catch (e: Exception) {
                "✗ 応答を読み取れません"
            }
            runOnUiThread { resultView.text = text }
        }
    }

    private fun runTest() {
        val token = tokenField.text.toString().trim().ifEmpty { prefs.token }
        val secret = secretField.text.toString().trim().ifEmpty { prefs.secret }
        val acId = acField.text.toString().trim()
        val lightId = lightField.text.toString().trim()
        resultView.text = "接続中…"
        pool.execute {
            val r = SwitchBotApi().listDevices(token, secret)
            val text = if (r.ok) describe(r.json, acId, lightId) else "✗ ${r.message}"
            runOnUiThread { resultView.text = text }
        }
    }

    /** デバイス一覧を表示し、入力したIDが一覧にあるかも確認する */
    private fun describe(json: JSONObject?, acId: String, lightId: String): String {
        val body = json?.optJSONObject("body")
        val ids = mutableSetOf<String>()
        val sb = StringBuilder("✓ 接続OK\n")
        val remotes = body?.optJSONArray("infraredRemoteList")
        sb.append("\n赤外線リモコン:\n")
        if (remotes != null) {
            for (i in 0 until remotes.length()) {
                val o = remotes.optJSONObject(i) ?: continue
                ids.add(o.optString("deviceId"))
                sb.append("・${o.optString("deviceName")} [${o.optString("remoteType")}] ${o.optString("deviceId")}\n")
            }
        }
        sb.append("\n")
        sb.append(if (acId in ids) "✓ エアコンのIDは一覧にあります\n" else "⚠ エアコンのIDが一覧にありません\n")
        sb.append(if (lightId in ids) "✓ 照明のIDは一覧にあります" else "⚠ 照明のIDが一覧にありません")
        return sb.toString()
    }

    override fun onDestroy() {
        pool.shutdown()
        super.onDestroy()
    }
}
