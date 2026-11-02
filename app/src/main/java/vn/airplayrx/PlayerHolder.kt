package vn.airplayrx

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Player nằm ngoài Activity để video vẫn chạy khi Activity bị ẩn. */
object PlayerHolder {
    private var p: ExoPlayer? = null
    private var pendingStart = 0.0
    var onState: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun <T> onMain(b: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return b()
        val f = FutureTask(b); main.post(f); return f.get(5, TimeUnit.SECONDS)
    }

    fun get(ctx: Context): ExoPlayer = onMain { p ?: build(ctx.applicationContext).also { p = it } }

    private fun build(ctx: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("AppleCoreMedia/1.0.0.20L497 (Apple TV; U; CPU OS 16_4 like Mac OS X; en_us)")
            .setAllowCrossProtocolRedirects(true)
        val ts = DefaultTrackSelector(ctx).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1920, 1080).setForceHighestSupportedBitrate(true))
        }
        val e = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setTrackSelector(ts)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        e.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY && pendingStart > 0 && e.duration != C.TIME_UNSET) {
                    e.seekTo((e.duration * pendingStart).toLong()); pendingStart = 0.0
                }
                emit()
            }
            override fun onPlayWhenReadyChanged(w: Boolean, r: Int) = emit()
        })
        return e
    }

    private fun emit() {
        val e = p ?: return
        val st = when (e.playbackState) {
            Player.STATE_ENDED -> "stopped"
            Player.STATE_BUFFERING -> "loading"
            Player.STATE_READY -> if (e.playWhenReady) "playing" else "paused"
            else -> return
        }
        onState?.invoke(st)
    }

    /** Video mới thay thế video cũ (setMediaItem tự dừng video đang chạy). */
    fun play(ctx: Context, url: String, startFraction: Double) {
        val e = get(ctx)
        onMain {
            pendingStart = startFraction
            e.setMediaItem(MediaItem.fromUri(url)); e.prepare(); e.playWhenReady = true
        }
    }
    fun setRate(v: Double) = onMain { p?.playWhenReady = v > 0 }
    fun seekSeconds(s: Double) = onMain { p?.seekTo((s * 1000).toLong()) }
    fun stop() = onMain { p?.stop(); p?.clearMediaItems() }
    /** duration, position (giây), rate */
    fun info(): Triple<Double, Double, Double> = onMain {
        val e = p
        if (e == null) Triple(0.0, 0.0, 0.0)
        else Triple(e.duration.coerceAtLeast(0) / 1000.0, e.currentPosition / 1000.0, if (e.isPlaying) 1.0 else 0.0)
    }
}
