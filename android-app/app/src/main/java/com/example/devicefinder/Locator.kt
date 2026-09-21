package com.example.devicefinder

import android.content.Context
import android.os.BatteryManager
import android.os.CountDownLatch
import android.os.SystemClock
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationClientOption.AMapLocationMode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 单次定位并上报。AMap 高精度一次定位（国内无 GMS 环境的首选），
 * 用 CountDownLatch 桥接回调，供推送/Broadcast/Worker 三种触发方同步调用。
 */
object Locator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** 阻塞式：取一次定位并上报。成功返回 true。请在非主线程调用。 */
    fun locateAndReport(context: Context, timeoutMs: Long = 15_000): Boolean {
        AMapPrivacy.ensure(context)
        val latch = CountDownLatch(1)
        var reported = false

        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationMode.Hight_Accuracy
            isOnceLocation = true          // 单次定位，拿到结果即停
            isNeedAddress = false          // 不做逆地理，省时
            isSensorEnable = true
        }
        val client = AMapLocationClient(context)
        client.setLocationOption(option)
        client.setLocationListener { loc: AMapLocation? ->
            try {
                if (loc != null && loc.errorCode == 0 && !reported) {
                    reported = report(
                        context,
                        loc.latitude, loc.longitude, loc.accuracy.toDouble()
                    )
                }
                // 定位失败(errorCode!=0)也退出，本次放弃；不编造位置
            } finally {
                latch.countDown()
            }
        }
        try {
            client.startLocation()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            client.stopLocation()
            client.onDestroy()
        }
        return reported
    }

    private fun report(context: Context, lat: Double, lng: Double, acc: Double): Boolean {
        val url = Prefs.serverUrl(context)
        if (url.isBlank()) return false
        val battery = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val body = JSONObject().apply {
            put("device_id", Prefs.deviceId(context))
            put("lat", lat)
            put("lng", lng)
            put("acc", acc)
            put("battery", battery)
        }
        return post("$url/api/report", body)
    }

    fun post(url: String, body: JSONObject): Boolean = try {
        http.newCall(
            Request.Builder().url(url).post(body.toString().toRequestBody(json)).build()
        ).execute().use { it.isSuccessful }
    } catch (e: Exception) { false }

    fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) { null }
}
