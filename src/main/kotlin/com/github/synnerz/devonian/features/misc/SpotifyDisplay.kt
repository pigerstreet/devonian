package com.github.synnerz.devonian.features.misc

import com.github.synnerz.devonian.api.Scheduler
import com.github.synnerz.devonian.api.events.RenderOverlayEvent
import com.github.synnerz.devonian.commands.DevonianCommand
import com.github.synnerz.devonian.config.Categories
import com.github.synnerz.devonian.config.Config
import com.github.synnerz.devonian.config.ConfigData
import com.github.synnerz.devonian.config.json.JsonDataObject
import com.github.synnerz.devonian.hud.texthud.Marquee
import com.github.synnerz.devonian.hud.texthud.StylizedTextHud
import com.github.synnerz.devonian.hud.texthud.TextHudFamily
import com.github.synnerz.devonian.hud.texthud.TextHudFeature
import com.github.synnerz.devonian.utils.DebugLogger
import com.github.synnerz.devonian.utils.WindowUtils
import com.google.gson.JsonArray
import java.util.*
import java.util.concurrent.TimeUnit

object SpotifyDisplay : TextHudFeature(
    "spotifyDisplay",
    "shows current song playing, only Windows with Spotify app",
) {
    override var dirty = false
    private val SETTING_HIDE_NOT_OPEN = addSwitch(
        "hideClosed",
        true,
        "do not render the spotify display if spotify is not opened",
        "Hide If Not Opened",
    )
    private val SETTING_MAX_SONG_LENGTH = addSlider(
        "maxSongLength",
        100.0,
        0.0, 300.0,
        "",
        "Max Song Name Length",
    )
    private val SETTING_PREFIX = addTextInput(
        "prefix",
        "&2Spotify &7>&r ",
        "",
        "Spotify Prefix",
    )
    private val SETTING_FORMAT = addTextInput(
        "format",
        "&a%ARTIST% &7-&b %SONG%",
        "use %ARTIST% and %SONG% (or dont for some reason idfc)",
        "Spotify Format",
    )
    private val SETTING_ALTERNATE_SCROLLING = addSwitch(
        "alternate",
        false,
        "",
        "Alternate Marquee Scrolling",
    )
    private val SETTING_SCROLL_SPEED = addSlider(
        "scrollSpeed",
        80.0,
        0.0, 300.0,
        "",
        "Marquee Scroll Speed",
    )
    private val SETTING_LOGGER = ConfigData.Switch(
        "${configName}\$logger",
        false,
        null,
        "requires restart",
        "Spotify Logger",
        "Misc",
        emptySet(),
        isHidden,
    ).also {
        Config.registerCategory(it, Categories.DEBUG, "Misc")
    }

    override fun createHud(): StylizedTextHud = TextHudFamily(configName, this)

    override fun getEditText(): List<String> = throw UnsupportedOperationException()

    override fun setEditDisplay() {
        setDisplay("Never Gonna Give You Up", "Rick Astley")
    }

    val prefixHud = StylizedTextHud("spotifyDisplayPrefix", (hud as TextHudFamily).createChildProvider())
    val marqueeHud = Marquee("spotifyDisplayMarquee", (hud as TextHudFamily).createChildProvider())

    init {
        val hud = hud as TextHudFamily
        hud.children.add(prefixHud)
        hud.children.add(marqueeHud)
    }

    private val isWindows = WindowUtils.isWindows

    private val specialNames = mapOf(
        "Spotify Free" to "&cPaused",
        "Spotify Premium" to "&cPaused",
        "Spotify" to "&aAdvertisement",
        "NOT OPENED" to "&cNot Opened"
    )

    fun setDisplay(song: String, artist: String) {
        prefixHud.setLine(SETTING_PREFIX.get())
        marqueeHud.setLine(
            if (isWindows)
                specialNames[song] ?:
                SETTING_FORMAT.get()
                    .replace("%SONG%", song)
                    .replace("%ARTIST%", artist)
            else "&cNot on Windows"
        )
        marqueeHud.maxLen = SETTING_MAX_SONG_LENGTH.get()
        marqueeHud.alternate = SETTING_ALTERNATE_SCROLLING.get()
        marqueeHud.scrollSpeed = SETTING_SCROLL_SPEED.get()
    }

    private var song = ""
    private var artist = ""
    private var open = false
    private var cmdReg = false

    /** the hidden window gdiplus names after the exe, our anchor onto spotify's ui process */
    private const val ANCHOR_TITLE = "GDI+ Window (Spotify.exe)"
    private const val NOT_OPENED = "NOT OPENED"
    /** re-walk this often even when the cached handle still looks right, so a recycled one cannot stick */
    private const val REWALK_EVERY = 15

    private var cachedWindow = 0L
    private var cachedPid = 0
    private var pollsSinceWalk = 0
    private var user32Broken = false
    private val candidates = ArrayList<Candidate>()

    private class Candidate(val hwnd: Long, val pid: Int, val title: String)

    private val logger = DebugLogger("SpotifyLogger")

    override fun initialize() {
        if (SETTING_LOGGER.get()) {
            logger.startLogger()

            DevonianCommand.command.subcommand("dumpspotify") { _, _ ->
                logger.stopAndPrint()
                return@subcommand 1
            }
            cmdReg = true
        }
        SETTING_LOGGER.onChange {
            if (it && !cmdReg) {
                logger.startLogger()

                DevonianCommand.command.subcommand("dumpspotify") { _, _ ->
                    logger.stopAndPrint()
                    return@subcommand 1
                }
                cmdReg = true
            }
        }

        if (isWindows) {
            Scheduler.schedulePool.scheduleWithFixedDelay({
                if (!isEnabled()) return@scheduleWithFixedDelay
                if (isEditing) return@scheduleWithFixedDelay

                val obj = JsonDataObject()
                obj.set("time", System.currentTimeMillis())
                try {
                    poll(obj)
                } catch (e: Exception) {
                    obj.set("error", e.toString())
                }
                logger.offer(obj)
            }, 0L, 2L, TimeUnit.SECONDS)
        }

        on<RenderOverlayEvent> { event ->
            if (!open && SETTING_HIDE_NOT_OPEN.get()) return@on
            if (!isEditing) setDisplay(song, artist)
            draw(event.ctx)
        }
    }

    /**
     * spotify runs several processes and more than one of them owns a titled window, so neither the
     * first nor the last caption is the answer. anchor on [ANCHOR_TITLE], take that window's pid,
     * and only look at the captions belonging to it
     */
    private fun poll(obj: JsonDataObject) {
        val title = readFromWindows(obj) ?: readFromTasklist(obj) ?: run {
            // the desktop could not be read at all, which is not the same as spotify being closed
            obj.set("result", "unavailable")
            return
        }

        obj.set("title", title)
        open = title != NOT_OPENED
        applyTitle(title)
    }

    private fun applyTitle(title: String) {
        if (title in specialNames) {
            song = title
            return
        }
        val i = title.indexOf(" - ")
        if (i < 0) {
            artist = ""
            song = title
            return
        }
        artist = title.take(i)
        song = title.drop(i + 3)
    }

    /** the suffix on "Spotify" is the account tier, and a track is always "artist - song" */
    private fun looksLikeSpotify(title: String): Boolean =
        title.startsWith("Spotify") || title.contains(" - ")

    /** the caption, [NOT_OPENED] when spotify is closed, or null when the desktop was unreadable */
    private fun readFromWindows(obj: JsonDataObject): String? {
        if (user32Broken) return null

        try {
            val cached = readCachedWindow()
            if (cached != null) {
                obj.set("source", "cache")
                return cached
            }

            var anchorPid = 0
            candidates.clear()
            val walked = WindowUtils.walkTitledWindows { hwnd, pid, windowTitle ->
                if (windowTitle == ANCHOR_TITLE) anchorPid = pid
                else if (looksLikeSpotify(windowTitle)) candidates.add(Candidate(hwnd, pid, windowTitle))
            }

            val arr = JsonArray()
            for (c in candidates) arr.add("${c.pid} ${c.title}")
            obj.set("source", "walk")
            obj.set("walked", walked)
            obj.set("anchorPid", anchorPid)
            obj.set("candidates", arr)

            if (walked == 0) return null

            cachedWindow = 0L
            pollsSinceWalk = 0
            if (anchorPid == 0) return NOT_OPENED

            val hit = candidates.firstOrNull { it.pid == anchorPid } ?: return NOT_OPENED
            cachedWindow = hit.hwnd
            cachedPid = hit.pid
            return hit.title
        } catch (e: Throwable) {
            // user32 could not be reached, fall back to spawning tasklist for the rest of the session
            user32Broken = true
            obj.set("user32Error", e.toString())
            return null
        } finally {
            candidates.clear()
        }
    }

    /** re-reading one known handle is a few microseconds, walking every window is a fraction of a ms */
    private fun readCachedWindow(): String? {
        if (cachedWindow == 0L) return null
        if (pollsSinceWalk >= REWALK_EVERY) return null

        val title = WindowUtils.titleOf(cachedWindow) ?: return null
        if (!looksLikeSpotify(title)) return null
        if (WindowUtils.pidOf(cachedWindow) != cachedPid) return null

        pollsSinceWalk++
        return title
    }

    /**
     * only reached when the window walk is unusable. an appcontainer cannot enumerate processes at
     * all, so this is the path that fails under sandboxed launchers - never make it the primary one,
     * and never hand the child Redirect.DISCARD either, opening NUL is refused in there too
     */
    private fun readFromTasklist(obj: JsonDataObject): String? {
        val proc = try {
            ProcessBuilder(
                "cmd.exe", "/s", "/c",
                "chcp", "65001",
                "&&",
                "tasklist.exe",
                "/fo", "csv",
                "/nh",
                "/v",
                "/fi", "\"IMAGENAME eq Spotify.exe\""
            ).start()
        } catch (e: Exception) {
            obj.set("tasklistError", e.toString())
            return null
        }

        obj.set("source", "tasklist")
        try {
            val arr = JsonArray()
            obj.set("lines", arr)

            val sc = Scanner(proc.inputStream, Charsets.UTF_8)
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                arr.add(line)
                if (line == "INFO: No tasks are running which match the specified criteria.") break

                val parts = line.split("\",\"")
                if (parts.size < 9) continue
                val name = parts.drop(8).joinToString("\",\"").dropLast(1).trim()

                // the same filter the walk uses. the first non "N/A" row is just as likely to be a
                // hidden helper window as it is to be the one showing the track
                if (looksLikeSpotify(name)) return name
            }

            return NOT_OPENED
        } finally {
            // the success path used to return before any of this ran, leaking three pipes a poll
            try { proc.inputStream.close() } catch (_: Exception) {}
            try { proc.errorStream.close() } catch (_: Exception) {}
            try { proc.outputStream.close() } catch (_: Exception) {}
            if (!proc.waitFor(2L, TimeUnit.SECONDS)) proc.destroy()
        }
    }
}
