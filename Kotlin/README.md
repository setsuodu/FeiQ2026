# MiniFeiQ Android

飞秋 / IPMsg 兼容客户端（Android 版），由原 Compose Desktop 工程移植。

## 功能

- **UDP 局域网**：端口 2425，GBK，可与飞秋 2013 / 原 WPF 客户端互通
- **WebSocket 中继**：连接同一 RelayService，互联网模式
- 文字消息、文件收发（UDP 走 TCP:2425 拉文件；WS 走 MFQ 分片）
- Jetpack Compose UI

## 如何打包 APK

1. 用 **Android Studio**（推荐 Hedgehog / Ladybug 或更新）打开本目录 `MiniFeiQ-Android`
2. 等待 Gradle Sync 完成
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. 完成后 APK 在：  
   `app/build/outputs/apk/debug/app-debug.apk`
5. 拷到手机安装即可（需允许未知来源）

命令行（已配置 SDK 与 JAVA_HOME 时）：

```bash
./gradlew :app:assembleDebug
```

## 注意

- 需要同一 Wi-Fi 才能 UDP 互通；手机与 PC 防火墙放行 2425 UDP/TCP
- WebSocket 中继地址按实际填写，例如 `ws://你的服务器:9000/ws`
- 下载文件保存在应用私有目录 `files/downloads/`
- 发送文件通过系统文件选择器（SAF）

## 工程结构

```
app/
  src/main/java/minifeiq/
    protocol/     IPMSG 协议与 MFQ 分片
    transport/    UdpTransport / WebSocketTransport (OkHttp)
    service/      IpMsgService
    ui/           Compose 界面
    MainActivity.kt
```

原 Desktop 工程：https://github.com/setsuodu/MiniFeiQ/tree/main/Kotlin
