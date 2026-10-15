package local.smartdisplay

import android.content.Context

/**
 * 設定とエアコン状態の保存。アプリ専用領域（MODE_PRIVATE）に置き、
 * AndroidManifest の allowBackup=false でバックアップにも含めない。
 * トークン/シークレットはここ（Kotlin側）だけが持ち、画面(JS)には渡さない。
 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("smartdisplay", Context.MODE_PRIVATE)

    private fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    private fun putStr(key: String, value: String) = sp.edit().putString(key, value).apply()

    var token: String
        get() = str("token", "")
        set(v) = putStr("token", v)

    var secret: String
        get() = str("secret", "")
        set(v) = putStr("secret", v)

    var acDeviceId: String
        get() = str("acId", DEFAULT_AC_ID)
        set(v) = putStr("acId", v)

    var lightDeviceId: String
        get() = str("lightId", DEFAULT_LIGHT_ID)
        set(v) = putStr("lightId", v)

    /** SwitchBotアプリで登録した照明のカスタムボタン名 */
    var nightCmd: String
        get() = str("nightCmd", "常夜灯")
        set(v) = putStr("nightCmd", v)

    var allCmd: String
        get() = str("allCmd", "全灯")
        set(v) = putStr("allCmd", v)

    /** 明るさボタン長押し時の送信間隔(ms)。Python版の BRIGHTNESS_PULSE_INTERVAL_MS 相当 */
    var pulseMs: Int
        get() = sp.getInt("pulseMs", 600)
        set(v) = sp.edit().putInt("pulseMs", v).apply()

    /** 無操作でこの秒数が過ぎたら待機画面へ。0 = 自動では待機しない（手動ボタンのみ） */
    var idleSec: Int
        get() = sp.getInt("idleSec", 60)
        set(v) = sp.edit().putInt("idleSec", v).apply()

    /** 天気（Open-Meteo）の緯度・経度・モデル。既定は東京・jma_msm。モデルが空なら指定しない */
    var wxLat: String
        get() = str("wxLat", "35.6895")
        set(v) = putStr("wxLat", v)

    var wxLon: String
        get() = str("wxLon", "139.6917")
        set(v) = putStr("wxLon", v)

    var wxModel: String
        get() = str("wxModel", "jma_msm")
        set(v) = putStr("wxModel", v)

    /** PC連携（USB経由で PC 側プログラムと通信）。トークンが空なら PC連携は使わない */
    var pcToken: String
        get() = str("pcToken", "")
        set(v) = putStr("pcToken", v)

    var pcPort: Int
        get() = sp.getInt("pcPort", 8765)
        set(v) = sp.edit().putInt("pcPort", v).apply()

    /** ビジュアライザー: 画面側で60fpsに補完する / ピークホールドを表示する */
    var visSmooth: Boolean
        get() = sp.getBoolean("visSmooth", true)
        set(v) = sp.edit().putBoolean("visSmooth", v).apply()

    /** VIS待機画面のレイアウト（1: 上に時計・下に棒 / 2: PC待機と同じ左の時計 + 右に棒） */
    var visLayout: Int
        get() = sp.getInt("visLayout", 1)
        set(v) = sp.edit().putInt("visLayout", v).apply()

    var visPeak: Boolean
        get() = sp.getBoolean("visPeak", false)
        set(v) = sp.edit().putBoolean("visPeak", v).apply()

    /** Google カレンダー / ToDo（読み取り専用）。クライアントID・シークレット・リフレッシュトークンは Kotlin 側だけが持つ */
    var googleClientId: String
        get() = str("gClientId", "")
        set(v) = putStr("gClientId", v)

    var googleClientSecret: String
        get() = str("gClientSecret", "")
        set(v) = putStr("gClientSecret", v)

    var googleRefresh: String
        get() = str("gRefresh", "")
        set(v) = putStr("gRefresh", v)

    /** 期日のない ToDo も概要に出すか */
    var taskUndated: Boolean
        get() = sp.getBoolean("taskUndated", false)
        set(v) = sp.edit().putBoolean("taskUndated", v).apply()

    val googleConfigured: Boolean
        get() = googleClientId.isNotBlank() && googleClientSecret.isNotBlank() && googleRefresh.isNotBlank()

    /** エアコンの最後に送った設定(JSON)。Python版の config.json 相当 */
    var state: String
        get() = str("state", "{}")
        set(v) = putStr("state", v)

    val configured: Boolean
        get() = token.isNotBlank() && secret.isNotBlank() &&
            acDeviceId.isNotBlank() && lightDeviceId.isNotBlank()

    companion object {
        // デバイスIDは設定画面で入力します（初期値は空）
        const val DEFAULT_AC_ID = ""
        const val DEFAULT_LIGHT_ID = ""
    }
}
