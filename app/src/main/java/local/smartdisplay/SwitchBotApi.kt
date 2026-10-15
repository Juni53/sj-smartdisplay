package local.smartdisplay

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SwitchBot API v1.1 クライアント。Python版 SwitchBotAPI クラスと同じ署名・同じリクエスト形式。
 *   sign = Base64( HMAC-SHA256(key=secret, msg=token + t + nonce) )
 */
class SwitchBotApi {

    data class Result(val ok: Boolean, val message: String, val json: JSONObject? = null)

    /** コマンド送信。成功判定は Python版と同じく応答JSONの statusCode == 100 */
    fun sendCommand(
        token: String,
        secret: String,
        deviceId: String,
        command: String,
        parameter: String,
        commandType: String,
    ): Result {
        if (deviceId.isBlank()) return Result(false, "デバイスIDが未設定です（設定画面）")
        val body = JSONObject()
            .put("command", command)
            .put("parameter", parameter)
            .put("commandType", commandType)
            .toString()
        return call(token, secret, "POST", "/v1.1/devices/$deviceId/commands", asciiOnly(body))
    }

    /** 接続テスト用。副作用なしでデバイス一覧を取得する */
    fun listDevices(token: String, secret: String): Result =
        call(token, secret, "GET", "/v1.1/devices", null)

    private fun sign(token: String, secret: String, t: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal((token + t + nonce).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /** Python の json.dumps (ensure_ascii=True) と同じく、非ASCII文字を \uXXXX にする */
    private fun asciiOnly(json: String): String {
        val sb = StringBuilder()
        for (c in json) {
            if (c.code < 128) sb.append(c) else sb.append(String.format("\\u%04x", c.code))
        }
        return sb.toString()
    }

    private fun call(token: String, secret: String, method: String, path: String, body: String?): Result {
        if (token.isBlank() || secret.isBlank()) {
            return Result(false, "トークン/シークレットが未設定です（右上の設定ボタン）")
        }
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(HOST + path).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Authorization", token)
            c.setRequestProperty("sign", sign(token, secret, t, nonce))
            c.setRequestProperty("t", t)
            c.setRequestProperty("nonce", nonce)
            c.setRequestProperty("Content-Type", "application/json; charset=utf8")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                null
            }
            if (json != null && json.optInt("statusCode") == 100) {
                Result(true, "成功", json)
            } else {
                val msg = json?.optString("message").orEmpty().ifEmpty { "HTTP $code" }
                Result(false, "エラー: $msg", json)
            }
        } catch (e: SocketTimeoutException) {
            Result(false, "タイムアウト")
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val HOST = "https://api.switch-bot.com"
    }
}
