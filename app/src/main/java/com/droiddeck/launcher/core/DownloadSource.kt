package com.droiddeck.launcher.core

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a download may come from. GitHub serves its release assets and raw files from hosts that
 * are unreachable on some networks; a prefix proxy in front of the same URL serves the same bytes,
 * so a stalled request can be handed to one of those instead of failing.
 *
 * The order is a preference, not a promise: [MODE_DIRECT] asks for the plain URL alone, the other
 * modes put the chosen source in front of it, and [Downloader] walks the list until one answers.
 * [MODE_AUTO] times the sources once when the launcher opens and leads with the quickest one.
 */
object DownloadSource {
    const val MODE_AUTO = "auto"
    const val MODE_DIRECT = "direct"
    const val MODE_MIRROR = "mirror"
    const val MODE_CUSTOM = "custom"

    private const val PREFS = "download-source"
    private const val KEY_MODE = "mode"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_FASTEST = "fastest"

    private const val PROBE = "https://raw.githubusercontent.com/Droid-Deck/DroidDeck/main/README.md"
    private const val PROBE_TIMEOUT_MS = 4_000
    private const val PROBE_COOLDOWN_MS = 30_000L
    private const val UA = "DroidDeck-Android"

    private val MODES = listOf(MODE_AUTO, MODE_DIRECT, MODE_MIRROR, MODE_CUSTOM)

    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "api.github.com",
    )

    /** [api] marks the proxies that also forward api.github.com; the others refuse it. */
    data class Mirror(val host: String, val api: Boolean)

    val MIRRORS = listOf(
        Mirror("gh-proxy.com", true),
        Mirror("gh-proxy.org", true),
        Mirror("ghproxy.net", false),
        Mirror("ghfast.top", false),
    )

    @Volatile
    private var app: Context? = null

    /** Host to try first: the quickest from the last probe, or the last source that worked. */
    @Volatile
    private var lastGood: String? = null

    private val probing = AtomicBoolean(false)
    private val probedAt = AtomicLong(0L)

    fun init(context: Context) {
        app = context.applicationContext
        lastGood = prefs()?.getString(KEY_FASTEST, null)
    }

    /** Times every source in parallel and remembers the quickest one, so a download does not guess. */
    fun probe() {
        if (app == null || !probing.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        if (now - probedAt.get() < PROBE_COOLDOWN_MS) {
            probing.set(false)
            return
        }
        probedAt.set(now)
        Thread {
            try {
                val times = ConcurrentHashMap<String, Long>()
                val sources = listOf<String?>(null) + MIRRORS.map { it.host }
                val latch = CountDownLatch(sources.size)
                for (mirror in sources) {
                    Thread {
                        try {
                            val ms = latency(mirror?.let { "https://$it/$PROBE" } ?: PROBE)
                            if (ms > 0) times[mirror ?: ""] = ms
                        } finally {
                            latch.countDown()
                        }
                    }.apply { isDaemon = true }.start()
                }
                latch.await(PROBE_TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS)
                val best = times.minByOrNull { it.value }?.key ?: return@Thread
                lastGood = best
                prefs()?.edit()?.putString(KEY_FASTEST, best)?.apply()
            } finally {
                probing.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun latency(url: String): Long {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-1023")
                setRequestProperty("User-Agent", UA)
            }
            connection = c
            if (c.responseCode / 100 != 2) 0L else {
                c.inputStream.use { it.read() }
                (System.nanoTime() - started) / 1_000_000
            }
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    fun isGithub(url: String): Boolean = GITHUB_HOSTS.any { url.startsWith("https://$it/") }

    fun candidates(target: String): List<String> {
        val prefs = prefs() ?: return listOf(target)
        if (!isGithub(target)) return listOf(target)
        val api = target.startsWith("https://api.github.com/")
        val proxies = MIRRORS.filter { !api || it.api }.map { it.proxy(target) }
        val ordered = when (prefs.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO) {
            MODE_DIRECT -> listOf(target)
            MODE_MIRROR -> listOfNotNull(picked(prefs, api)?.proxy(target)) + quickest(listOf(target) + proxies)
            MODE_CUSTOM -> listOfNotNull(customProxy(prefs, target)) + quickest(listOf(target) + proxies)
            else -> quickest(listOf(target) + proxies)
        }
        return ordered.distinct()
    }

    private fun quickest(sources: List<String>): List<String> =
        sources.sortedBy { if (host(it) == lastGood) 0 else 1 }

    fun succeeded(source: String) {
        lastGood = host(source)
    }

    /** Called when every source failed, so the next download does not start on the one that just died. */
    fun failed() {
        lastGood = null
        prefs()?.edit()?.remove(KEY_FASTEST)?.apply()
    }

    fun mode(context: Context): String = prefs(context)?.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setMode(context: Context, mode: String) {
        prefs(context)?.edit()?.putString(KEY_MODE, mode.takeIf { it in MODES } ?: MODE_AUTO)?.apply()
    }

    /** The mirror in effect, which is the first one until the user picks another. */
    fun mirror(context: Context): String {
        val host = prefs(context)?.getString(KEY_MIRROR, "") ?: ""
        return MIRRORS.firstOrNull { it.host == host }?.host ?: MIRRORS.first().host
    }

    fun setMirror(context: Context, host: String) {
        prefs(context)?.edit()?.putString(KEY_MIRROR, host.takeIf { h -> MIRRORS.any { it.host == h } } ?: "")?.apply()
    }

    fun custom(context: Context): String = prefs(context)?.getString(KEY_CUSTOM, "") ?: ""

    fun setCustom(context: Context, prefix: String) {
        prefs(context)?.edit()?.putString(KEY_CUSTOM, normalizePrefix(prefix))?.apply()
    }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefs(context: Context): SharedPreferences? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Mirror.proxy(target: String): String = "https://$host/$target"

    private fun host(url: String): String =
        MIRRORS.firstOrNull { url.startsWith("https://${it.host}/") }?.host ?: ""

    private fun picked(prefs: SharedPreferences, api: Boolean): Mirror? {
        val chosen = MIRRORS.firstOrNull { it.host == (prefs.getString(KEY_MIRROR, "") ?: "") } ?: MIRRORS.firstOrNull()
        return if (api && chosen != null && !chosen.api) MIRRORS.firstOrNull { it.api } else chosen
    }

    private fun custom(prefs: SharedPreferences): String = prefs.getString(KEY_CUSTOM, "") ?: ""

    private fun customProxy(prefs: SharedPreferences, target: String): String? =
        normalizePrefix(custom(prefs)).takeIf { it.isNotEmpty() }?.let { "$it/$target" }

    private fun normalizePrefix(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
