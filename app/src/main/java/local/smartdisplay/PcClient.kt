package local.smartdisplay

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC（Windows）で動かす PC 側プログラムとの通信。
 * USB で adb reverse をしてあるので、スマホから見た PC は http://127.0.0.1:ポート になる。
 * 通信先は 127.0.0.1 固定、合言葉（トークン）つき、使える窓口は決まったものだけ。
 */
class PcClient(private val prefs: Prefs) {

    data class Result(val ok: Boolean, val message: String, val body: String? = null)

    private fun url(path: String) = URL("http://127.0.0.1:${prefs.pcPort}$path")

    fun request(method: String, path: String, body: String): Result {
        if (path !in ALLOWED) return Result(false, "許可されていない操作です")
        if (prefs.pcToken.isBlank()) return Result(false, "PCのトークンが未設定です")
        return try {
            val c = url(path).openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 4000
            c.requestMethod = method
            c.setRequestProperty("X-Token", prefs.pcToken)
            if (method == "POST") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            when {
                code in 200..299 -> Result(true, "OK", text)
                code == 401 -> Result(false, "トークンが違います")
                else -> Result(false, (try { JSONObject(text).optString("error") } catch (e: Exception) { "" }).ifEmpty { "HTTP $code" })
            }
        } catch (e: Exception) {
            Result(false, "PCに接続できません")
        }
    }

    /** 棒グラフの値（1行 = 1フレーム「12;45;...」）を、止められるまで読み続ける。接続が切れたら例外で戻る。 */
    fun stream(isCurrent: () -> Boolean, setConn: (HttpURLConnection?) -> Unit, onLine: (String) -> Unit) {
        val c = url("/vis").openConnection() as HttpURLConnection
        setConn(c)
        try {
            c.connectTimeout = 1500
            c.readTimeout = 5000
            c.setRequestProperty("X-Token", prefs.pcToken)
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (isCurrent()) {
                    val line = r.readLine() ?: break
                    onLine(line)
                }
            }
        } finally {
            setConn(null)
            c.disconnect()
        }
    }

    companion object {
        val ALLOWED = setOf("/status", "/media", "/volume")
    }
}
