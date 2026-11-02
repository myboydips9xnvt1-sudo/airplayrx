package vn.airplayrx

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class AirPlayService : Service() {
    companion object {
        @Volatile var ip = "?"
        @Volatile var regAirplay = "chưa đăng ký"
        @Volatile var regRaop = "chưa đăng ký"
        @Volatile var http = "đang khởi động"
        val status: String get() = "IP: $ip:${AirPlayServer.PORT}\nHTTP: $http\nmDNS airplay: $regAirplay\nmDNS raop: $regRaop"
    }
    private var server: AirPlayServer? = null
    private var jmdns: JmDNS? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (server != null) return START_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("airplay", "AirPlay", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("airplay_play", "Video mới", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "airplay").setContentTitle("AirPlay Receiver đang chạy nền")
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
            showPlayer()
        }.also { it.onStatus = { s -> http = s }; it.start() }
        PlayerHolder.onState = { server?.sendEvent(it) }
        registerMdns(devId)
        return START_STICKY
    }

    /** Đưa màn hình phát video lên trước, kể cả khi app đang chạy ẩn. */
    private fun showPlayer() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) { startActivity(i); return } } catch (_: Exception) {}
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "airplay_play").setContentTitle("Có video AirPlay mới — chạm để xem")
            .setSmallIcon(android.R.drawable.ic_media_play).setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun localAddress(): InetAddress? {
        val ifs = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
        for (i in ifs) for (a in i.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) return a
        return null
    }

    private fun registerMdns(devId: String) {
        Thread {
            try {
                val addr = localAddress() ?: throw Exception("không có địa chỉ mạng")
                ip = addr.hostAddress ?: "?"
                val pk = MessageDigest.getInstance("SHA-256").digest(devId.toByteArray()).joinToString("") { "%02x".format(it) }
                val pi = UUID.nameUUIDFromBytes(devId.toByteArray()).toString()
                val name = "AirPlay ${Build.MODEL}"
                val ft = AirPlayServer.FEATURES_TXT
                val air = mapOf("deviceid" to devId, "features" to ft, "srcvers" to "220.68", "flags" to "0x4", "vv" to "2",
                    "model" to "AppleTV3,2", "pw" to "false", "rhd" to "5.6.0.0", "pk" to pk, "pi" to pi, "rmodel" to "PC1.0")
                val raop = mapOf("txtvers" to "1", "ch" to "2", "cn" to "0,1,2,3", "da" to "true", "et" to "0,3,5", "vv" to "2",
                    "ft" to ft, "am" to "AppleTV3,2", "md" to "0,1,2", "rhd" to "5.6.0.0", "pw" to "false", "sr" to "44100",
                    "ss" to "16", "sv" to "false", "tp" to "UDP", "vn" to "65537", "vs" to "220.68", "rmodel" to "PC1.0",
                    "sf" to "0x4", "pk" to pk)
                val j = JmDNS.create(addr, "AirPlay-" + devId.replace(":", ""))
                jmdns = j
                j.registerService(ServiceInfo.create("_airplay._tcp.local.", name, AirPlayServer.PORT, 0, 0, air)); regAirplay = "OK"
                j.registerService(ServiceInfo.create("_raop._tcp.local.", devId.replace(":", "") + "@" + name, AirPlayServer.PORT, 0, 0, raop)); regRaop = "OK"
            } catch (e: Exception) { regAirplay = "LỖI " + e.message; regRaop = regAirplay }
        }.start()
    }

    private fun deviceId(): String {
        val a = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "0"
        return a.padEnd(12, '0').take(12).uppercase().chunked(2).joinToString(":")
    }

    override fun onDestroy() {
        val j = jmdns; Thread { try { j?.unregisterAllServices(); j?.close() } catch (_: Exception) {} }.start()
        server?.stop(); multicast?.release(); wifi?.release(); wake?.release()
        super.onDestroy()
    }
}
