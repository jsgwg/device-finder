# 设备找回（Device Finder）— 国内无 GMS 版

自有设备防丢定位：App 平时完全休眠（无前台服务、无通知栏、零耗电），通过三条通道触发单次定位上报。

## 三条触发通道（国内环境组合拳）

| 通道 | 速度 | 说明 |
|---|---|---|
| 极光 JPush 透传 | 秒级 | 面板点「定位」即下发；自定义消息不显示通知 |
| WorkManager 轮询 | ≤15 分钟 | 兜底主通道：国产 ROM 杀进程后推送不可靠，周期任务可靠执行且无通知 |
| 短信触发 | 秒级 | 终极兜底：向本机发 `FIND#<设备ID>`，只需蜂窝网络 |

任何一条通道生效都会完成一次「定位 → 上报 → 结束」，结果相同。

```
device-finder/
├── server/          # FastAPI 服务端 + 指令队列 + Web 面板
│   ├── main.py
│   ├── index.html
│   └── requirements.txt
└── android-app/     # Kotlin 客户端（JPush + 高德定位 + WorkManager + 短信）
```

## 服务端部署

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

可选配置 `server/jpush.json`（秒级下发；不配则依赖设备 15 分钟轮询消费指令）：

```json
{"app_key": "你的极光AppKey", "master_secret": "你的MasterSecret"}
```

## Android 客户端配置

1. **高德 Key**：[高德开放平台](https://lbs.amap.com) 创建应用 → 添加 Key（平台选 Android，填包名 `com.example.devicefinder` + 签名 SHA1）。**本项目使用固定签名**（`android-app/device-finder.keystore`，已随仓库提交），SHA1 固定为：

   ```
   7C:68:95:C9:39:FC:98:83:71:8D:AD:D6:4F:FA:3D:32:6E:7A:7A:DC
   ```

   填入 `AndroidManifest.xml` 的 `com.amap.api.v2.apikey`
2. **极光 AppKey**：[极光控制台](https://www.jiguang.cn) 创建应用（包名同上），AppKey 填入 `JPUSH_APPKEY`；服务端 `jpush.json` 用同一应用的 AppKey + MasterSecret
3. 编译 APK（无需 Android Studio，见下一节）
4. 首页：填服务器地址 → 注册设备 → 授予精确定位 → 后台定位选「始终允许」→（可选）授予短信权限
5. GPS 开关保持开启（系统不允许远程打开）

## 编译 APK：GitHub Actions 云端构建（无需 Android Studio）

项目已带 `.github/workflows/build.yml`，推送到 GitHub 后自动编译：

1. 在 GitHub 新建一个 **Private** 仓库（如 `device-finder`）
2. 本地推送（Windows 自带 git，或装个 Git 即可）：

   ```bash
   cd D:/Users/wx_data/device-finder
   git init && git add -A && git commit -m "device finder"
   git remote add origin https://github.com/<你的用户名>/device-finder.git
   git push -u origin main
   ```

   > 改了 Manifest 里的高德 Key / 极光 AppKey 后再推送，构建出的 APK 即为成品
3. 打开仓库 **Actions** 页 → 等待「Build APK」跑完（约 3~5 分钟）→ 点进该次运行 → 底部 **Artifacts** 下载 `DeviceFinder-apk`，解压即得 APK
4. 也可以在 Actions 页面手动点 **Run workflow** 随时重新构建

签名说明：`app/build.gradle` 中 debug/release 都绑定 `device-finder.keystore`（密码 `DeviceFinder@2026`），本地、云端构建的 APK 签名一致，覆盖安装互不冲突；如需换签名，删除 keystore 重新生成并同步更新高德 Key 的 SHA1。

## 触发指令

- Web 面板：`http://<服务器IP>:8000`，设备列表点「定位」
- 短信：任意手机向设备号码发送 `FIND#<设备ID>`（设备 ID 在 App 首页显示）

## 合规边界

- 仅限安装在**自己名下**的设备，应用图标保持可见
- 不要分发给他人隐蔽安装——隐蔽监控违反《个人信息保护法》并可能触刑
