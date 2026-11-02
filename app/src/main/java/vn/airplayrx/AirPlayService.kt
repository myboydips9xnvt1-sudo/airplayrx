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
import java.security.MessageDigest
import java.util.UUID

class AirPlayService : Service() {
    companion object {
        @Volatile var ip = "?"
        @Volatile var regAirplay = "chưa đăng ký"
        @Volatile var regRaop = "chưa đăng ký"
        @Volatile var http = "đang khởi động"
        val status: String get() = "IP: $ip:${AirPlayServer.PORT}\nHTTP: $http\nmDNS airplay: $regAirplay\nmDNS raop: $regRaop"
    }
    private var server: AirPlayServer? = null
    private var nsd: NsdManager? = null
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()
    private var multicast: WifiManager.MulticastLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
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
        val a = w.connectionInfo.ipAddress
        ip = if (a == 0) "không có Wi-Fi" else "${a and 255}.${a shr 8 and 255}.${a shr 16 and 255}.${a shr 24 and 255}"

        val devId = deviceId()
        PlayerHolder.get(this)
        server = AirPlayServer(devId) { url, start ->
            PlayerHolder.play(this, url, start)
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }.also { it.onStatus = { s -> http = s }; it.start() }
        PlayerHolder.onState = { server?.sendEvent(it) }
        registerNsd(devId)
        return START_STICKY
    }

    private fun listener(cb: (String) -> Unit) = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(s: NsdServiceInfo) = cb("OK (${s.serviceName})")
        override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) = cb("LỖI mã $e")
        override fun onServiceUnregistered(s: NsdServiceInfo) {}
        override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
    }.also { listeners.add(it) }

    private fun registerNsd(devId: String) {
        val pk = MessageDigest.getInstance("SHA-256").digest(devId.toByteArray()).joinToString("") { "%02x".format(it) }
        val pi = UUID.nameUUIDFromBytes(devId.toByteArray()).toString()
        val name = "AirPlay ${Build.MODEL}"
        val ft = AirPlayServer.FEATURES_TXT
        val air = NsdServiceInfo().apply {
            serviceName = name; serviceType = "_airplay._tcp"; port = AirPlayServer.PORT
            mapOf("deviceid" to devId, "features" to ft, "srcvers" to "220.68", "flags" to "0x4", "vv" to "2",
                "model" to "AppleTV3,2", "pw" to "false", "rhd" to "5.6.0.0", "pk" to pk, "pi" to pi, "rmodel" to "PC1.0")
                .forEach { (k, v) -> setAttribute(k, v) }
        }
        val raop = NsdServiceInfo().apply {
            serviceName = devId.replace(":", "") + "@" + name; serviceType = "_raop._tcp"; port = AirPlayServer.PORT
            mapOf("txtvers" to "1", "ch" to "2", "cn" to "0,1,2,3", "da" to "true", "et" to "0,3,5", "vv" to "2",
                "ft" to ft, "am" to "AppleTV3,2", "md" to "0,1,2", "rhd" to "5.6.0.0", "pw" to "false", "sr" to "44100",
                "ss" to "16", "sv" to "false", "tp" to "UDP", "vn" to "65537", "vs" to "220.68", "rmodel" to "PC1.0",
                "sf" to "0x4", "pk" to pk).forEach { (k, v) -> setAttribute(k, v) }
        }
        val m = getSystemService(NSD_SERVICE) as NsdManager
        nsd = m
        m.registerService(air, NsdManager.PROTOCOL_DNS_SD, listener { regAirplay = it })
        m.registerService(raop, NsdManager.PROTOCOL_DNS_SD, listener { regRaop = it })
    }

    private fun deviceId(): String {
        val a = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "0"
        return a.padEnd(12, '0').take(12).uppercase().chunked(2).joinToString(":")
    }

    override fun onDestroy() {
        listeners.forEach { try { nsd?.unregisterService(it) } catch (_: Exception) {} }
        server?.stop(); multicast?.release(); wifi?.release(); wake?.release()
        super.onDestroy()
    }
}
