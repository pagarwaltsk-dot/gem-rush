package com.gemrush.quiz

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Gem Rush host/join shell.
 * The game itself is the web page in assets/index.html. This activity adds what a web page can't do:
 *  - Host: start a local-only hotspot and run the game "hub" (a WebSocket relay on port 8765)
 *  - Join: scan the host's QR code and connect to the host's hotspot
 *  - Tilt: read the phone's gravity sensor and pass it to the page
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var hub: Hub? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var sensors: SensorManager? = null
    private var tiltOn = false
    private var lastTilt = 0L
    private var afterPerms: (() -> Unit)? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents
        send("scan", if (text == null) JSONObject().put("cancel", true) else JSONObject().put("text", text))
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val next = afterPerms
            afterPerms = null
            next?.invoke()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            // Links to websites and WhatsApp open outside the app; the game itself stays inside
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "file") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // target="_blank" links (e.g. Share on WhatsApp) arrive here
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val href = view.hitTestResult.extra ?: return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
                } catch (_: Exception) {
                }
                return false
            }
        }
        web.settings.setSupportMultipleWindows(true)
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onResume() {
        super.onResume()
        if (tiltOn) registerTilt()
    }

    override fun onPause() {
        super.onPause()
        sensors?.unregisterListener(this)
    }

    override fun onDestroy() {
        stopHosting()
        netCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    /** Sends an event to the page: GR.native(type, data) */
    private fun send(type: String, data: JSONObject) {
        main.post { web.evaluateJavascript("window.GR&&GR.native('$type',$data)", null) }
    }

    private fun withPerms(perms: List<String>, then: () -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else {
            afterPerms = then
            permLauncher.launch(missing.toTypedArray())
        }
    }

    /* ---------------- page -> app bridge ---------------- */

    inner class Bridge {
        @JavascriptInterface
        fun startHost() {
            main.post {
                val perms = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                withPerms(perms) { startHosting() }
            }
        }

        @JavascriptInterface
        fun stopHost() {
            main.post { stopHosting() }
        }

        @JavascriptInterface
        fun scan() {
            main.post {
                val opts = ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR code on the host's phone")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                scanLauncher.launch(opts)
            }
        }

        @JavascriptInterface
        fun joinWifi(ssid: String, pass: String, sec: Int) {
            main.post { joinHotspot(ssid, pass, sec) }
        }

        @JavascriptInterface
        fun tilt(on: Boolean) {
            main.post {
                tiltOn = on
                if (on) registerTilt() else sensors?.unregisterListener(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 500).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {
            }
        }
    }

    /* ---------------- hosting ---------------- */

    private fun startHub(): Boolean {
        if (hub != null) return true
        return try {
            val h = Hub(PORT)
            h.isReuseAddr = true
            h.start()
            hub = h
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startHosting() {
        if (!startHub()) {
            send("host", JSONObject().put("ok", false).put("err", "could not open the game room"))
            return
        }
        if (reservation != null) {
            reportHost(null)
            return
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    main.postDelayed({ reportHost(null) }, 1500)
                }

                override fun onFailed(reason: Int) {
                    val why = when (reason) {
                        ERROR_INCOMPATIBLE_MODE -> "turn off your normal mobile hotspot first"
                        ERROR_TETHERING_DISALLOWED -> "this phone does not allow hotspots"
                        ERROR_NO_CHANNEL -> "no free Wi-Fi channel"
                        else -> "turn on Location and try again"
                    }
                    reportHost(why)
                }

                override fun onStopped() {
                    reservation = null
                }
            }, main)
        } catch (e: SecurityException) {
            reportHost("allow the Nearby devices / Location permission and turn on Location")
        } catch (e: Exception) {
            reportHost(e.message ?: "hotspot error")
        }
    }

    /** Tells the page how players can reach this phone. If the hotspot failed, falls back to the current Wi-Fi. */
    @Suppress("DEPRECATION")
    private fun reportHost(hotspotError: String?) {
        val r = reservation
        var ssid = ""
        var pass = ""
        var sec = 1
        if (r != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = r.softApConfiguration
                ssid = c.ssid ?: ""
                pass = c.passphrase ?: ""
                sec = c.securityType
            } else {
                val c = r.wifiConfiguration
                ssid = c?.SSID?.trim('"') ?: ""
                pass = c?.preSharedKey?.trim('"') ?: ""
            }
        }
        val ip = localIp(r != null)
        val out = JSONObject().put("ssid", ssid).put("pass", pass).put("sec", sec).put("ip", ip)
        if (r == null && hotspotError != null) {
            if (ip.isEmpty()) {
                send("host", out.put("ok", false).put("err", hotspotError))
                return
            }
            out.put("note", "Hotspot did not start ($hotspotError). Players must be on the same Wi-Fi as you.")
        }
        send("host", out.put("ok", true))
    }

    private fun stopHosting() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
        try {
            hub?.stop(300)
        } catch (_: Exception) {
        }
        hub = null
    }

    /** This phone's local IPv4 address; prefers the hotspot interface when one is running. */
    private fun localIp(preferHotspot: Boolean): String {
        val found = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) found.add(ni.name to (a.hostAddress ?: ""))
                }
            }
        } catch (_: Exception) {
        }
        val hotspotNames = listOf("ap", "swlan", "softap", "wlan1", "wlan2")
        if (preferHotspot) {
            found.firstOrNull { (n, _) -> hotspotNames.any { n.startsWith(it) } }?.let { return it.second }
        }
        found.firstOrNull { (n, _) -> n.startsWith("wlan") }?.let { return it.second }
        return found.firstOrNull { it.second.startsWith("192.168.") }?.second ?: found.firstOrNull()?.second ?: ""
    }

    /* ---------------- joining ---------------- */

    private fun joinHotspot(ssid: String, pass: String, sec: Int) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 29) {
            val b = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (pass.isNotEmpty()) {
                if (sec == 3) b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass)
            }
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(b.build())
                .build()
            netCallback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
            }
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var reported = false
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                    main.postDelayed({
                        if (!reported) {
                            reported = true
                            send("wifi", JSONObject().put("ok", true).put("ip", gatewayOf(cm.getLinkProperties(network))))
                        }
                    }, 800)
                }

                override fun onUnavailable() {
                    send("wifi", JSONObject().put("ok", false).put("err", "could not connect. Try again, or join the host's hotspot from Wi-Fi settings and type the address"))
                }
            }
            netCallback = cb
            try {
                cm.requestNetwork(req, cb, 60000)
            } catch (e: Exception) {
                send("wifi", JSONObject().put("ok", false).put("err", e.message ?: "connection error"))
            }
        } else {
            joinLegacy(ssid, pass)
        }
    }

    private fun gatewayOf(lp: LinkProperties?): String {
        if (lp == null) return ""
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress?.let { return it }
        lp.routes.firstOrNull { it.gateway is Inet4Address && !(it.gateway?.isAnyLocalAddress ?: true) }?.gateway?.hostAddress?.let { return it }
        if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress?.let { return it }
        return ""
    }

    /** Android 8 and 9 */
    @Suppress("DEPRECATION")
    private fun joinLegacy(ssid: String, pass: String) {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            if (!wm.isWifiEnabled) wm.isWifiEnabled = true
            val conf = WifiConfiguration().apply {
                SSID = "\"" + ssid + "\""
                if (pass.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                else preSharedKey = "\"" + pass + "\""
            }
            val id = wm.addNetwork(conf)
            if (id == -1) {
                send("wifi", JSONObject().put("ok", false).put("err", "this phone refused the hotspot"))
                return
            }
            wm.disconnect()
            wm.enableNetwork(id, true)
            wm.reconnect()
        } catch (e: Exception) {
            send("wifi", JSONObject().put("ok", false).put("err", e.message ?: "connection error"))
            return
        }
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                tries++
                val onIt = wm.connectionInfo?.ssid?.trim('"') == ssid
                val gw = wm.dhcpInfo?.gateway ?: 0
                if (onIt && gw != 0) {
                    val ip = "${gw and 0xff}.${(gw shr 8) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 24) and 0xff}"
                    send("wifi", JSONObject().put("ok", true).put("ip", ip))
                } else if (tries < 25) {
                    main.postDelayed(this, 1000)
                } else {
                    send("wifi", JSONObject().put("ok", false).put("err", "timed out joining the hotspot"))
                }
            }
        }
        main.postDelayed(poll, 1500)
    }

    /* ---------------- tilt ---------------- */

    private fun registerTilt() {
        val sm = sensors ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastTilt < 50) return
        lastTilt = now
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val g = sqrt(x * x + y * y + z * z)
        if (g < 1f) return
        // Same meaning as the browser's deviceorientation: beta = front/back tilt, gamma = left/right tilt
        val beta = Math.toDegrees(asin((y / g).coerceIn(-1f, 1f).toDouble()))
        val gamma = -Math.toDegrees(asin((x / g).coerceIn(-1f, 1f).toDouble()))
        send("tilt", JSONObject().put("beta", beta).put("gamma", gamma))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val PORT = 8765
    }
}

/**
 * The game hub, running on the host phone. It only passes messages along:
 *  - the host page connects with ?role=host
 *  - players connect with ?role=player&id=...&name=...
 *  - player messages go to the host as {t:"_msg", from, data}; joins/leaves as {t:"_join"} / {t:"_leave"}
 *  - host messages {to: id | "all", data} go to that player / everyone
 */
class Hub(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    @Volatile
    private var host: WebSocket? = null
    private val players = ConcurrentHashMap<String, WebSocket>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val q = Uri.parse("http://hub" + handshake.resourceDescriptor)
        if (q.getQueryParameter("role") == "host") {
            conn.setAttachment(HOST_TAG)
            host = conn
            return
        }
        val id = q.getQueryParameter("id")
        if (id.isNullOrEmpty()) {
            conn.close()
            return
        }
        val name = q.getQueryParameter("name") ?: "Player"
        conn.setAttachment(id)
        val old = players.put(id, conn)
        if (old != null && old !== conn) old.close()
        host?.let { safeSend(it, JSONObject().put("t", "_join").put("id", id).put("name", name).toString()) }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val id: String = conn.getAttachment() ?: return
        if (id == HOST_TAG) {
            try {
                val o = JSONObject(message)
                val data = o.optJSONObject("data")?.toString() ?: return
                val to = o.optString("to")
                if (to == "all") players.values.forEach { safeSend(it, data) }
                else players[to]?.let { safeSend(it, data) }
            } catch (_: Exception) {
            }
        } else {
            host?.let { safeSend(it, "{\"t\":\"_msg\",\"from\":" + JSONObject.quote(id) + ",\"data\":" + message + "}") }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val id: String = conn.getAttachment() ?: return
        if (id == HOST_TAG) {
            if (host === conn) host = null
            return
        }
        if (players.remove(id, conn)) {
            host?.let { safeSend(it, JSONObject().put("t", "_leave").put("id", id).toString()) }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}

    override fun onStart() {
        connectionLostTimeout = 10
    }

    private fun safeSend(c: WebSocket, s: String) {
        try {
            if (c.isOpen) c.send(s)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val HOST_TAG = "#host"
    }
}
