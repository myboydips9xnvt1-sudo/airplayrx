package vn.airplayrx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {
    private lateinit var view: PlayerView
    private lateinit var hint: TextView
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(s: Int) = updateHint()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        view = PlayerView(this).apply { setBackgroundColor(Color.BLACK); controllerShowTimeoutMs = 3000; useController = true }
        hint = TextView(this).apply {
            text = "Sẵn sàng nhận AirPlay\nTrên iPhone chọn: AirPlay ${Build.MODEL}"
            setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER
        }
        setContentView(FrameLayout(this).apply { addView(view); addView(hint) })
        val svc = Intent(this, AirPlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        val p = PlayerHolder.get(this); view.player = p; p.addListener(listener); updateHint()
    }
    override fun onStop() {
        PlayerHolder.get(this).removeListener(listener); view.player = null // âm thanh/video vẫn chạy trong service
        super.onStop()
    }
    override fun onResume() { super.onResume(); hideSystemUi() }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideSystemUi() }

    private fun updateHint() {
        val s = view.player?.playbackState ?: Player.STATE_IDLE
        hint.visibility = if (s == Player.STATE_IDLE || s == Player.STATE_ENDED) View.VISIBLE else View.GONE
    }
    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
       } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // Bàn phím/chuột trong DeX: ←/→ tua 10s, Space tạm dừng
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        val p = view.player ?: return super.onKeyDown(k, e)
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_RIGHT -> p.seekTo(p.currentPosition + 10_000)
            KeyEvent.KEYCODE_SPACE -> p.playWhenReady = !p.playWhenReady
            else -> return super.onKeyDown(k, e)
        }
        return true
    }
}
