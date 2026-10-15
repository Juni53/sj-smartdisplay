package local.smartdisplay

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google カレンダーと ToDo（Tasks）の読み取り専用の取得。画面(JS)から外部へは出られないので Kotlin 側で行う。
 * 認証は「リフレッシュトークン」で、アクセストークンをその都度（期限が切れたら）取り直す。
 * 設定した3つの値（クライアントID・シークレット・リフレッシュトークン）は画面(JS)には渡さない。
 */
class GoogleApi(private val prefs: Prefs) {

    data class Result(val ok: Boolean, val message: String, val body: String? = null)

    private class ApiError(val msg: String) : Exception(msg)

    @Volatile private var accessToken = ""
    @Volatile private var expiresAt = 0L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, url: String, form: String? = null, bearer: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.requestMethod = method
            c.setRequestProperty("User-Agent", "SmartDisplay/1.0")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (form != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return text
            val j = try { JSONObject(text) } catch (e: Exception) { null }
            val detail = j?.optJSONObject("error")?.optString("message")
                ?: j?.optString("error_description")?.takeIf { it.isNotEmpty() }
                ?: j?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: ""
            if (code == 400 && j?.optString("error") == "invalid_grant")
                throw ApiError("Googleの認証が切れています（google_auth.py をやり直して取り込み直してください）")
            if (code == 401) throw ApiError("Googleの認証に失敗しました")
            throw ApiError("HTTP $code ${detail.take(80)}".trim())
        } finally {
            c.disconnect()
        }
    }

    private fun token(): String {
        if (accessToken.isNotEmpty() && System.currentTimeMillis() < expiresAt - 60_000) return accessToken
        val form = "client_id=" + enc(prefs.googleClientId) + "&client_secret=" + enc(prefs.googleClientSecret) +
            "&refresh_token=" + enc(prefs.googleRefresh) + "&grant_type=refresh_token"
        val j = JSONObject(call("POST", "https://oauth2.googleapis.com/token", form))
        accessToken = j.getString("access_token")
        expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3000) * 1000
        return accessToken
    }

    private fun get(url: String): JSONObject {
        return try {
            JSONObject(call("GET", url, bearer = token()))
        } catch (e: ApiError) {
            if (e.msg.startsWith("Googleの認証に失敗")) { accessToken = ""; JSONObject(call("GET", url, bearer = token())) } else throw e
        }
    }

    /** timeMin / timeMax は RFC3339（UTCの Z つき）。今日と明日を含む範囲を渡す。 */
    fun fetch(timeMin: String, timeMax: String): Result {
        if (!prefs.googleConfigured) return Result(false, "Googleの設定がありません")
        val events = JSONArray()
        val tasks = JSONArray()
        var errCal = ""
        var errTask = ""
        try {
            token()
        } catch (e: ApiError) {
            return Result(false, e.msg)
        } catch (e: Exception) {
            return Result(false, "Googleに接続できません")
        }
        try {
            val cals = get("https://www.googleapis.com/calendar/v3/users/me/calendarList?minAccessRole=reader&maxResults=50&fields=items(id,summary,selected)")
                .optJSONArray("items") ?: JSONArray()
            var n = 0
            for (i in 0 until cals.length()) {
                val cal = cals.getJSONObject(i)
                if (!cal.optBoolean("selected", false)) continue
                if (++n > 15) break
                val url = "https://www.googleapis.com/calendar/v3/calendars/" + enc(cal.getString("id")) + "/events" +
                    "?timeMin=" + enc(timeMin) + "&timeMax=" + enc(timeMax) +
                    "&singleEvents=true&orderBy=startTime&maxResults=100" +
                    "&fields=" + enc("items(summary,status,start,end,attendees(self,responseStatus))")
                val items = get(url).optJSONArray("items") ?: continue
                for (k in 0 until items.length()) {
                    val ev = items.getJSONObject(k)
                    if (ev.optString("status") == "cancelled") continue
                    val att = ev.optJSONArray("attendees")
                    var declined = false
                    if (att != null) for (a in 0 until att.length()) {
                        val x = att.getJSONObject(a)
                        if (x.optBoolean("self", false) && x.optString("responseStatus") == "declined") declined = true
                    }
                    if (declined) continue
                    val st = ev.optJSONObject("start") ?: continue
                    val en = ev.optJSONObject("end")
                    val allDay = st.has("date")
                    events.put(JSONObject()
                        .put("title", ev.optString("summary").ifEmpty { "（タイトルなし）" })
                        .put("allDay", allDay)
                        .put("start", if (allDay) st.optString("date") else st.optString("dateTime"))
                        .put("end", if (en == null) "" else if (allDay) en.optString("date") else en.optString("dateTime")))
                }
            }
        } catch (e: ApiError) {
            errCal = e.msg
        } catch (e: Exception) {
            errCal = "カレンダーを読み取れません"
        }
        try {
            val lists = get("https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=20&fields=items(id)")
                .optJSONArray("items") ?: JSONArray()
            for (i in 0 until lists.length()) {
                val id = lists.getJSONObject(i).getString("id")
                val items = get("https://tasks.googleapis.com/tasks/v1/lists/" + enc(id) + "/tasks" +
                    "?showCompleted=false&showHidden=false&maxResults=100&fields=" + enc("items(title,due,status)"))
                    .optJSONArray("items") ?: continue
                for (k in 0 until items.length()) {
                    val t = items.getJSONObject(k)
                    if (t.optString("status") == "completed") continue
                    val title = t.optString("title").trim()
                    if (title.isEmpty()) continue
                    tasks.put(JSONObject().put("title", title).put("due", t.optString("due").take(10)))
                }
            }
        } catch (e: ApiError) {
            errTask = e.msg
        } catch (e: Exception) {
            errTask = "ToDoを読み取れません"
        }
        if (errCal.isNotEmpty() && errTask.isNotEmpty()) return Result(false, errCal)
        val out = JSONObject().put("events", events).put("tasks", tasks)
            .put("errCal", errCal).put("errTask", errTask).put("undated", prefs.taskUndated)
        return Result(true, "OK", out.toString())
    }
}
