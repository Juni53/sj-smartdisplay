package local.smartdisplay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Open-Meteo から時間ごとの天気を取得する（画面(JS)から外部へは出られないので Kotlin 側で行う） */
class WeatherApi {

    data class Result(val ok: Boolean, val message: String, val body: String? = null)

    fun fetch(lat: String, lon: String, model: String): Result {
        val la = lat.trim().toDoubleOrNull()
        val lo = lon.trim().toDoubleOrNull()
        if (la == null || la < -90.0 || la > 90.0) return Result(false, "緯度が正しくありません")
        if (lo == null || lo < -180.0 || lo > 180.0) return Result(false, "経度が正しくありません")
        val m = model.trim().filter { it.isLetterOrDigit() || it == '_' || it == ',' || it == '-' }
        val url = StringBuilder("https://api.open-meteo.com/v1/forecast")
            .append("?latitude=").append(la)
            .append("&longitude=").append(lo)
            .append("&hourly=temperature_2m,weathercode,precipitation,wind_speed_10m,relative_humidity_2m")
            .append("&past_days=1&forecast_days=2")
        if (m.isNotEmpty()) url.append("&models=").append(URLEncoder.encode(m, "UTF-8"))
        url.append("&timezone=auto")   // 緯度・経度の場所の現地時間で返る
        return try {
            val c = URL(url.toString()).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "SmartDisplay/1.0")
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            if (code in 200..299) Result(true, "OK", text)
            else {
                val reason = try { JSONObject(text).optString("reason") } catch (e: Exception) { "" }
                Result(false, "HTTP $code ${reason.ifEmpty { "" }}".trim())
            }
        } catch (e: Exception) {
            Result(false, "通信できません（${e.javaClass.simpleName}）")
        }
    }
}
