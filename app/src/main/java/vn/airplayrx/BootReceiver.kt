package vn.airplayrx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED)
            try { c.startForegroundService(Intent(c, AirPlayService::class.java)) } catch (_: Exception) {}
    }
}
