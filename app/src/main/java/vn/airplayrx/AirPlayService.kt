package vn.airplayrx

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings

class AirPlayService : Service() {
    private var server: AirPlayServer? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (server != null) return START_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("airplay", "AirPlay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "airplay").setContentTitle("AirPlay Receiver đang chạy")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)

        val w = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicast = w.createMulticastLock("airplay").apply { setReferenceCounted(false); acquire() }
        wifi = w.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay")
            .apply { setReferenceCounted(false); acquire() }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:cpu").apply { acquire() }

        val devId = deviceId()
        PlayerHolder.get(this)
        server = AirPlayServer(devId) { url, start ->
            PlayerHolder.play(this, url, start)
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }.also { it.start() }
        PlayerHolder.onState = { server?.sendEvent(it) }
        registerNsd(devId)
        return START_STICKY
    }

    private fun registerNsd(devId: String) {
        val info = NsdServiceInfo().apply {
            serviceName = "AirPlay ${Build.MODEL}"; serviceType = "_airplay._tcp"; port = AirPlayServer.PORT
            setAttribute("deviceid", devId); setAttribute("features", "0x" + AirPlayServer.FEATURES.toString(16))
            setAttribute("model", "AppleTV3,2"); setAttribute("srcvers", "220.68"); setAttribute("flags", "0x4"); setAttribute("vv", "2")
        }
        reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
    }

    private fun deviceId(): String {
        val a = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "0"
        return a.padEnd(12, '0').take(12).uppercase().chunked(2).joinToString(":")
    }

    override fun onDestroy() {
        try { reg?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        server?.stop(); multicast?.release(); wifi?.release(); wake?.release()
        super.onDestroy()
    }
}
