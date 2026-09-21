package com.example.devicefinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 短信触发兜底：向本机发送内容为「FIND#<设备ID>」的短信触发一次定位。
 * 适用场景：推送全挂、设备只有蜂窝网络时的最后手段。
 * 广播接收器约 10 秒执行上限，定位回调用 CountDownLatch 等待。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val myId = Prefs.deviceId(context)

        val hit = Telephony.Sms.Intents.getMessagesFromIntent(intent).any {
            it.displayMessageBody?.trim()?.uppercase() == "FIND#$myId"
        }
        if (!hit) return

        val pending = goAsync()
        Thread {
            try {
                runBlocking { withTimeoutOrNull(9_000) { Locator.locateAndReport(context) } }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
