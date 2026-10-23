package dev.plume

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray

/**
 * Aerial flyover videos (Apple TV, Fire TV and community collections) as a live wallpaper, streamed while the
 * home screen is in front and only when chosen. The video list comes from Couchy Launcher (GPL-3.0,
 * github.com/conreo/couchy-launcher), assets/aerials.json: t = title, u = 1080p H.264 URL, s = collection.
 * Wallpaper choice "aerial:<collection>", "aerial:" for all of them.
 */
internal object Aerials {
    const val PREFIX = "aerial:"
    /** Collection key -> shown name; "" = all. C1/C2 (community) only play as part of "all". */
    val COLLECTIONS = linkedMapOf("A" to "Apple TV", "Z" to "Fire TV", "" to null)

    @Volatile private var all: List<Pair<String, String>>? = null

    fun urls(context: Context, collection: String): List<String> {
        val list = all ?: JSONArray(context.assets.open("aerials.json").use { it.readBytes().decodeToString() }).let { a ->
            List(a.length()) { a.getJSONObject(it).let { e -> e.optString("s", "A") to e.getString("u") } }
        }.also { all = it }
        return list.filter { collection.isEmpty() || it.first == collection }.map { it.second }
    }
}

/**
 * Plays shuffled aerials, muted, filling the screen, under the same shading a photo wallpaper gets ([Shade]).
 * Two TextureViews take turns: the next video starts on the hidden one and fades in over the last frame of the
 * one before, so there's never a black gap. TextureView rather than SurfaceView: it fades and stacks under
 * Plume's ordinary window like any view. Everything is released in [stop], so nothing decodes or streams
 * while an app is open.
 *
 * ExoPlayer, not the framework MediaPlayer: Apple's aerials are QuickTime files with their index at the end,
 * and MediaPlayer's extractor sniffs them over HTTP so slowly that its watchdog kills it (seen on a SHIELD).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AerialPlayer(private val context: Context, private val stage: FrameLayout, private val shade: View) {
    private val ui = Handler(Looper.getMainLooper())
    private val views = Array(2) { TextureView(context).apply { alpha = 0f } }
    private val players = arrayOfNulls<ExoPlayer>(2)
    private var front = -1 // the slot on screen, -1 before the first video shows
    private var urls = emptyList<String>()
    private val deck = ArrayDeque<String>()
    private var failures = 0
    var playing: String? = null // the collection being played, null when stopped
        private set

    fun start(collection: String) {
        if (playing == collection) return
        stop()
        urls = try {
            Aerials.urls(context, collection)
        } catch (e: Exception) {
            emptyList()
        }
        if (urls.isEmpty()) return
        playing = collection
        failures = 0
        shade.alpha = 0f // the still under the video has its own shading; this one comes in with the first frame
        for (v in views) stage.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
        play(0)
    }

    fun stop() {
        playing = null
        ui.removeCallbacksAndMessages(null)
        for (i in 0..1) release(i)
        shade.animate().cancel()
        for (v in views) {
            v.animate().cancel()
            v.alpha = 0f
            stage.removeView(v)
        }
        front = -1
    }

    private fun release(slot: Int) {
        players[slot]?.let { p -> players[slot] = null; p.release() }
    }

    private fun next(): String {
        if (deck.isEmpty()) deck += urls.shuffled().filter { it != deck.lastOrNull() }
        return deck.removeFirst()
    }

    private fun play(slot: Int) {
        release(slot)
        val url = next()
        val view = views[slot]
        players[slot] = ExoPlayer.Builder(context).build().apply {
            volume = 0f
            setVideoTextureView(view)
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() = show(slot)
                override fun onVideoSizeChanged(size: VideoSize) = fill(view, size.width, size.height)
                override fun onPlaybackStateChanged(state: Int) {
                    // This one holds its last frame until the next fades in.
                    if (state == Player.STATE_ENDED && players[slot] === this@apply) play(1 - slot)
                }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.w("Plume", "aerial failed (${error.errorCodeName}): $url")
                    retry(slot)
                }
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    /** One video that won't play (gone from its CDN, network blip) skips to another; a run of them gives up for now. */
    private fun retry(slot: Int) {
        release(slot)
        if (playing == null || ++failures > MAX_FAILURES) return
        ui.postDelayed({ if (playing != null) play(slot) }, 2000L * failures)
    }

    private fun show(slot: Int) {
        failures = 0
        val old = front
        if (old == slot) return // same player, e.g. a new first frame after a stall
        front = slot
        stage.bringChildToFront(views[slot])
        shade.bringToFront()
        if (old < 0) shade.animate().alpha(1f).setDuration(FADE_MS).start()
        views[slot].animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            if (old >= 0 && front == slot) {
                views[old].alpha = 0f
                release(old)
            }
        }.start()
    }

    /**
     * Centre-crop, like the photo wallpapers. ExoPlayer already letterboxes the video into the view with its
     * own transform; this replaces it with one that scales the video up until it covers the view instead.
     */
    private fun fill(view: TextureView, videoW: Int, videoH: Int) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (videoW == 0 || videoH == 0 || w == 0f || h == 0f) return
        val scale = maxOf(w / videoW, h / videoH)
        view.setTransform(Matrix().apply { setScale(videoW * scale / w, videoH * scale / h, w / 2, h / 2) })
    }

    private companion object {
        const val FADE_MS = 1200L
        const val MAX_FAILURES = 4
    }
}

/**
 * The shading over a live wallpaper. Lighter than paintShades' for pictures: that one fades into solid ground
 * colour, which on moving footage reads as a black (or white) wall over half the screen. Here the video stays
 * visible everywhere; the ground colour [base] only goes on thick enough to keep the clock and the rows legible.
 * In dark mode that's black and the footage gets a light [dim]; in light mode it's the soft white, under dark text.
 */
internal class Shade(context: Context, private val base: Int, private val dim: Int, private val fade: Boolean) : View(context) {
    private val top = Paint().apply { isDither = true }
    private val bottom = Paint().apply { isDither = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val c = base and 0x00FFFFFF
        fun a(alpha: Int) = c or (alpha shl 24)
        top.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(a(TOP), a(TOP / 2), c), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        // Clear down to just above the cards' title, then a scrim behind the rows, a little thicker at the very bottom.
        bottom.shader = LinearGradient(0f, h * 0.38f, 0f, h.toFloat(), intArrayOf(c, a(ROWS), a(FLOOR)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (dim != 0) canvas.drawColor(dim)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h * 0.3f, top)
        if (fade) canvas.drawRect(0f, h * 0.38f, w, h, bottom)
    }

    companion object {
        const val DIM = 38 // dark mode: black over all the footage (15%)
        const val TOP = 0x66 // behind the clock and top bar (40%)
        const val ROWS = 0x80 // behind Continue Watching (50%)
        const val FLOOR = 0xA6 // behind the apps row (65%)
    }
}
