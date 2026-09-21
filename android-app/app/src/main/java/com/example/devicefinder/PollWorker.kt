package com.example.devicefinder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 轮询兜底：每 15 分钟向服务端取一次待执行指令。
 * JPush 透传在国产 ROM 杀进程后不可靠，这条通道保证最终能收到定位指令。
 * WorkManager 周期任务不用前台服务，无通知栏显示。
 */
class PollWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = Prefs.serverUrl(applicationContext)
        if (url.isNotBlank()) {
            val resp = Locator.get("$url/api/pending/${Prefs.deviceId(applicationContext)}")
            if (resp != null) {
                val obj = JSONObject(resp)
                if (obj.optBoolean("has_pending") && obj.optBoolean("locate")) {
                    Locator.locateAndReport(applicationContext)
                }
            }
        }
        Result.success()
    }
}
