package com.example.devicefinder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.jpush.android.api.JPushInterface
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "服务器地址" })
        val urlInput = EditText(this).apply {
            hint = "http://192.168.1.100:8000"
            setText(Prefs.serverUrl(this@MainActivity))
            singleLine = true
        }
        root.addView(urlInput)
        root.addView(Button(this).apply {
            text = "注册设备"
            setOnClickListener {
                Prefs.serverUrl(this@MainActivity) = urlInput.text.toString().trim().trimEnd('/')
                register()
            }
        })
        root.addView(Button(this).apply {
            text = "1. 授予精确定位"
            setOnClickListener { ensurePermission(Manifest.permission.ACCESS_FINE_LOCATION) }
        })
        root.addView(Button(this).apply {
            text = "2. 后台定位（选「始终允许」）"
            setOnClickListener { openAppDetails() }
        })
        root.addView(Button(this).apply {
            text = "3. 授予短信触发权限（可选兜底）"
            setOnClickListener { ensurePermission(Manifest.permission.RECEIVE_SMS) }
        })
        root.addView(TextView(this).apply {
            text = "设备ID：${Prefs.deviceId(this@MainActivity)}\n" +
                   "短信触发格式：FIND#${Prefs.deviceId(this@MainActivity)}\n\n" +
                   "注意：GPS 开关无法远程打开，请保持开启；\n" +
                   "后台定位选「始终允许」后息屏也能定位。"
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)

        schedulePolling()
    }

    private fun schedulePolling() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "locate-poll",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    private fun ensurePermission(perm: String) {
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "已授予", Toast.LENGTH_SHORT).show(); return
        }
        ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
    }

    private fun openAppDetails() {
        startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ))
    }

    private fun register() {
        val url = Prefs.serverUrl(this)
        if (url.isBlank()) { Toast.makeText(this, "请先填服务器地址", Toast.LENGTH_SHORT).show(); return }

        // 极光别名 = 设备ID，服务端按别名定向下发
        JPushInterface.setAlias(this, 1, Prefs.deviceId(this))

        thread {
            val body = JSONObject().apply {
                put("device_id", Prefs.deviceId(this@MainActivity))
                put("model", Build.MODEL)
            }
            val ok = ServerApi.post("$url/api/register", body)
            runOnUiThread {
                Toast.makeText(this, if (ok) "注册成功" else "注册失败，检查服务器地址", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

object Prefs {
    private const val FILE = "finder"
    fun serverUrl(c: Context): String =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("url", "") ?: ""
    fun serverUrl(c: Context, v: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("url", v).apply()
    }
    fun deviceId(c: Context): String =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}

object ServerApi {
    private val http = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** 返回是否 2xx */
    fun post(url: String, body: JSONObject): Boolean = try {
        http.newCall(
            Request.Builder().url(url).post(body.toString().toRequestBody(json)).build()
        ).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }
}
