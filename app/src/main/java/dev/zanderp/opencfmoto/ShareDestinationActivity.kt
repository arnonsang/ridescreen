// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * "Share" a place from Google Maps (or any app) to RideScreen, or open a geo: link with it: the
 * place becomes the destination on the built-in map, on the dash when the bike is connected.
 * No screen of its own.
 */
class ShareDestinationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        if (text.isNullOrBlank()) { finish(); return }
        Toast.makeText(this, R.string.share_dest_finding, Toast.LENGTH_SHORT).show()
        Thread({
            val place = try { SharedPlace.resolve(text) } catch (e: Exception) {
                LogBus.log("[SHARE] could not read the shared place: ${e.javaClass.simpleName}"); null
            }
            runOnUiThread {
                if (place == null) {
                    Toast.makeText(this, R.string.share_dest_failed, Toast.LENGTH_LONG).show()
                } else {
                    LogBus.log("[SHARE] destination from another app: ${place.name}")
                    ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
                    ProjectionHolder.projection = null
                    MapPlaces.pushHistory(this, place)
                    GpxSession.prepareNavTo(place)
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(MainActivity.EXTRA_START_GPX, true)
                            putExtra(MainActivity.EXTRA_GPX_TO_BIKE, true)
                        },
                    )
                }
                finish()
            }
        }, "share-dest").start()
    }
}

/** Turns shared text or a link into a place. The parsing is pure so it can be unit-tested. */
object SharedPlace {
    private val URL_RE = Regex("""https?://\S+""")
    private val COORD = """(-?\d{1,3}\.\d+)"""
    private val PATTERNS = listOf(
        Regex("""!3d$COORD!4d$COORD"""),          // the pin of a Google Maps place link
        Regex("""[?&](?:q|ll|query|destination)=$COORD(?:,|%2C)$COORD"""),
        Regex("""@$COORD,$COORD"""),               // the map's centre: least exact, so last
    )

    /** Coordinates from a geo: URI or a maps URL, or null. */
    internal fun coords(s: String): Pair<Double, Double>? {
        if (s.startsWith("geo:", ignoreCase = true)) {
            Regex("""^geo:$COORD,$COORD""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
                val p = m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
                if (p.first != 0.0 || p.second != 0.0) return p
            }
            Regex("""[?&]q=$COORD,$COORD""").find(s)?.let { return it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            return null
        }
        for (re in PATTERNS) {
            val m = re.find(s) ?: continue
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }

    /** A readable name: the text before the link, else the place segment of the URL, else null. */
    internal fun name(text: String, url: String?): String? {
        val before = URL_RE.replace(text, "").lines().map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (before != null) return before.take(80)
        val seg = url?.let { Regex("""/maps/place/([^/@?]+)""").find(it)?.groupValues?.get(1) }
        val q = url?.let { Regex("""[?&]q=([^&]+)""").find(it)?.groupValues?.get(1) }
        return (seg ?: q)?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }
            ?.replace('+', ' ')?.takeIf { coords("?q=$it") == null }?.take(80)
    }

    /** Follow a short link (maps.app.goo.gl …) to the full URL. Network; a few hops at most. */
    private fun expand(start: String): String {
        var url = start
        repeat(5) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000; readTimeout = 8000
                requestMethod = "GET"
            }
            try {
                val code = conn.responseCode
                val next = conn.getHeaderField("Location")
                if (code !in 300..399 || next.isNullOrBlank()) return url
                url = if (next.startsWith("http")) next else URL(URL(url), next).toString()
                if (coords(url) != null) return url
            } finally {
                conn.disconnect()
            }
        }
        return url
    }

    fun resolve(text: String): MapPlace? {
        val trimmed = text.trim()
        if (trimmed.startsWith("geo:", ignoreCase = true)) {
            val label = Regex("""[?&]q=[^(]*\(([^)]+)\)""").find(trimmed)?.groupValues?.get(1)
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }
            coords(trimmed)?.let { return MapPlace(label ?: "Shared place", it.first, it.second, "shared", "") }
        }
        val url = URL_RE.find(trimmed)?.value
        val full = url?.let { if (coords(it) != null) it else try { expand(it) } catch (_: Exception) { it } }
        val label = name(trimmed, full)
        full?.let { coords(it) }?.let { return MapPlace(label ?: "Shared place", it.first, it.second, "shared", "") }
        // No coordinates in the link: look the name up instead.
        val query = label ?: trimmed.takeIf { url == null }?.take(80) ?: return null
        val loc = RideData.lat?.let { la -> RideData.lon?.let { lo -> la to lo } }
        return NominatimSearch.search(query, loc?.first, loc?.second).firstOrNull()
    }
}
