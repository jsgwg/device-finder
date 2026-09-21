package com.example.devicefinder

import android.content.Context
import cn.jpush.android.api.CmdMessage
import cn.jpush.android.api.CustomMessage
import cn.jpush.android.service.JPushMessageReceiver

/**
 * 极光透传消息回调：收到自定义消息 {"action":"locate"} 触发一次定位上报。
 * 自定义消息不进通知栏。进程被杀且厂商通道未配置时可能收不到——由 PollWorker 轮询兜底。
 */
class JPushReceiver : JPushMessageReceiver() {

    override fun onMessage(context: Context, message: CustomMessage) {
        if (message.message == "locate") {
            Thread { Locator.locateAndReport(context) }.start()
        }
    }

    @Suppress("unused")
    private fun onCommand(context: Context, cmd: CmdMessage) { /* 保留：调试指令 */ }
}
