package com.example.devicefinder

import android.app.Application
import android.content.Context
import cn.jiguang.sdk.api.JCollectionAuth
import cn.jpush.android.api.JPushInterface

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 极光合规初始化（必须先于 init）
        JCollectionAuth.setAuth(this, true)
        JPushInterface.setDebugMode(false)
        JPushInterface.init(this)
        AMapPrivacy.ensure(this)
    }
}

/** 高德 SDK 隐私合规接口，必须在定位前调用 */
object AMapPrivacy {
    @Volatile private var done = false
    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            try {
                val cls = Class.forName("com.amap.api.location.AMapLocationClient")
                cls.getMethod(
                    "updatePrivacyShow", Context::class.java, Boolean::class.java, Boolean::class.java
                ).invoke(null, context, true, true)
                cls.getMethod(
                    "updatePrivacyAgree", Context::class.java, Boolean::class.java
                ).invoke(null, context, true)
                done = true
            } catch (_: Throwable) {
                // SDK 版本差异时降级：直接尝试定位，报隐私未同意错误再排查
            }
        }
    }
}
