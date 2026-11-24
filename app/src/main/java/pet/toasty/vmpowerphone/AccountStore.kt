package pet.toasty.vmpowerphone

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Account(val server: String, val token: String, val label: String)

data class Accounts(val list: List<Account> = emptyList(), val active: Int = 0) {
    val current: Account? get() = list.getOrNull(active)
}

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    fun load(): Accounts {
        val raw = prefs.getString("list", null) ?: return Accounts()
        return try {
            val arr = JSONArray(raw)
            val list = List(arr.length()) {
                val o = arr.getJSONObject(it)
                Account(o.getString("server"), o.getString("token"), o.getString("label"))
            }
            Accounts(list, prefs.getInt("active", 0).coerceIn(0, maxOf(0, list.size - 1)))
        } catch (e: Exception) {
            Accounts()
        }
    }

    fun save(a: Accounts) {
        val arr = JSONArray()
        a.list.forEach {
            arr.put(JSONObject().put("server", it.server).put("token", it.token).put("label", it.label))
        }
        prefs.edit().putString("list", arr.toString()).putInt("active", a.active).apply()
    }
}

fun normalizeServer(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    return s
}

fun labelFor(server: String, user: String?): String {
    val host = server.removePrefix("https://").removePrefix("http://")
    return if (user.isNullOrEmpty()) host else "$user@$host"
}

/** vmpower://login?server=<url-encoded>&token=<token>  ->  (server, token) */
fun parseLoginLink(text: String): Pair<String, String>? {
    val uri = try { Uri.parse(text.trim()) } catch (e: Exception) { return null }
    if (uri.scheme != "vmpower" || uri.host != "login") return null
    val server = uri.getQueryParameter("server") ?: return null
    val token = uri.getQueryParameter("token") ?: return null
    if (server.isBlank() || token.isBlank()) return null
    return normalizeServer(server) to token
}