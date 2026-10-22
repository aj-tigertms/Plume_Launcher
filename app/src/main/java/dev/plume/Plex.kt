package dev.plume

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Plex's On Deck, straight from your Plex servers. The Plex app on Android TV publishes only its own promotions
 * to the system's Watch Next list, so Plume asks the servers itself, the same way any Plex client does:
 * sign in once with a plex.tv/link code, look the servers up on plex.tv, then read each one's Continue
 * Watching hub over its fastest working connection (local, direct remote, or Plex's relay).
 * All calls block: run them off the UI thread. Nothing here runs unless you've signed in.
 */
internal object Plex {
    const val PKG = "com.plexapp.android"
    const val LINK_URL = "https://plex.tv/link"

    class Pin(val id: Long, val code: String)

    /** One On Deck entry; [season]/[episode] only for episodes. [intent] opens it in the Plex app. */
    class Item(
        val title: String, val season: String?, val episode: String?, val durationMs: Long, val offsetMs: Long,
        val image: String?, val intent: String, val viewedAt: Long,
    )

    private class Server(val id: String, val token: String, val uris: List<String>)

    fun signedIn(prefs: SharedPreferences) = prefs.contains(TOKEN)
    fun user(prefs: SharedPreferences) = prefs.getString(USER, null)

    fun signOut(prefs: SharedPreferences) {
        val edit = prefs.edit().remove(TOKEN).remove(USER)
        prefs.all.keys.filter { it.startsWith(URI_PREFIX) }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** A short code for plex.tv/link. Poll [claim] with it until it returns the account's token. */
    fun newPin(prefs: SharedPreferences): Pin {
        val o = JSONObject(call("https://plex.tv/api/v2/pins?strong=false", prefs, method = "POST"))
        return Pin(o.getLong("id"), o.getString("code"))
    }

    /** True once the code has been entered and the token saved; false while still waiting. */
    fun claim(prefs: SharedPreferences, pin: Pin): Boolean {
        val token = JSONObject(call("https://plex.tv/api/v2/pins/${pin.id}", prefs)).optString("authToken")
        if (token.isNullOrEmpty() || token == "null") return false
        val user = try {
            JSONObject(call("https://plex.tv/api/v2/user", prefs, token)).let { it.optString("username").ifEmpty { it.optString("title") } }
        } catch (e: Exception) {
            null
        }
        prefs.edit().putString(TOKEN, token).putString(USER, user).apply()
        return true
    }

    /** On Deck across every server you can reach, most recently watched first. Empty when signed out. */
    fun onDeck(prefs: SharedPreferences): List<Item> {
        val token = prefs.getString(TOKEN, null) ?: return emptyList()
        return servers(prefs, token).flatMap { s ->
            try {
                deck(prefs, s)
            } catch (e: Exception) {
                android.util.Log.w("Plume", "Plex On Deck failed for ${s.id} ($e)")
                emptyList()
            }
        }.sortedByDescending { it.viewedAt }.take(MAX_ITEMS)
    }

    private fun servers(prefs: SharedPreferences, token: String): List<Server> {
        val list = JSONArray(call("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", prefs, token))
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { "server" in it.optString("provides") }
            .map { r ->
                val conns = r.optJSONArray("connections") ?: JSONArray()
                val all = (0 until conns.length()).map { conns.getJSONObject(it) }
                // Fastest first: on the same network, then direct over the internet, then Plex's (slow) relay.
                val ordered = all.sortedBy { c -> if (c.optBoolean("relay")) 2 else if (c.optBoolean("local")) 0 else 1 }
                Server(r.getString("clientIdentifier"), r.optString("accessToken").ifEmpty { token }, ordered.map { it.getString("uri") })
            }
    }

    /** The server's Continue Watching hub (or the older On Deck list), via the connection that last worked. */
    private fun deck(prefs: SharedPreferences, s: Server): List<Item> {
        val known = prefs.getString(URI_PREFIX + s.id, null)
        for (base in listOfNotNull(known) + s.uris.filter { it != known }) {
            val json = try {
                try {
                    call("$base/hubs/continueWatching/items?X-Plex-Container-Size=$MAX_ITEMS", prefs, s.token, timeout = 4000)
                } catch (e: java.io.FileNotFoundException) { // older servers
                    call("$base/library/onDeck?X-Plex-Container-Size=$MAX_ITEMS", prefs, s.token, timeout = 4000)
                }
            } catch (e: Exception) {
                continue
            }
            if (base != known) prefs.edit().putString(URI_PREFIX + s.id, base).apply()
            val items = JSONObject(json).getJSONObject("MediaContainer").optJSONArray("Metadata") ?: return emptyList()
            return (0 until items.length()).map { items.getJSONObject(it) }.mapNotNull { m -> item(m, base, s) }
        }
        return emptyList()
    }

    private fun item(m: JSONObject, base: String, s: Server): Item? {
        val episode = m.optString("type") == "episode"
        val key = m.optString("ratingKey").ifEmpty { return null }
        // Wide art suits the 2:1 cards: the show's background for an episode, the film's own for a film.
        val art = listOf(if (episode) "grandparentArt" else "art", "art", "thumb", "grandparentThumb")
            .firstNotNullOfOrNull { k -> m.optString(k).takeIf { it.isNotEmpty() } }
        val image = art?.let {
            "$base/photo/:/transcode?width=640&height=360&minSize=1&upscale=1&url=${enc(it)}&X-Plex-Token=${enc(s.token)}"
        }
        // The Plex app's own route to an item's page (its episode or film screen, resume one OK away). Its older
        // plex://server://<id>/com.plexapp.plugins.library/... links only open the app's home now.
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("plex://libraries/${s.id}/metadata/$key")).setPackage(PKG)
        return Item(
            title = (if (episode) m.optString("grandparentTitle") else null)?.ifEmpty { null } ?: m.optString("title"),
            season = if (episode) m.optString("parentIndex").ifEmpty { null } else null,
            episode = if (episode) m.optString("index").ifEmpty { null } else null,
            durationMs = m.optLong("duration"),
            offsetMs = m.optLong("viewOffset"),
            image = image,
            intent = open.toUri(Intent.URI_INTENT_SCHEME),
            viewedAt = m.optLong("lastViewedAt"),
        )
    }

    private fun call(url: String, prefs: SharedPreferences, token: String? = null, method: String = "GET", timeout: Int = 8000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-Plex-Product", "Plume")
            c.setRequestProperty("X-Plex-Client-Identifier", clientId(prefs))
            c.setRequestProperty("X-Plex-Device-Name", "Plume (${android.os.Build.MODEL})")
            c.setRequestProperty("X-Plex-Platform", "Android")
            token?.let { c.setRequestProperty("X-Plex-Token", it) }
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    /** Plex identifies each app install by a stable id; it shows under Authorized Devices on plex.tv. */
    private fun clientId(prefs: SharedPreferences): String =
        prefs.getString(CLIENT, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(CLIENT, it).apply() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private const val TOKEN = "plex_token"
    private const val USER = "plex_user"
    private const val CLIENT = "plex_client"
    private const val URI_PREFIX = "plex_uri_" // + server id: the connection that worked last
    private const val MAX_ITEMS = 20
}
