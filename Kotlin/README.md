# MiniFeiQ Android

飞秋 / IPMsg 兼容客户端（Android 版），由原 Android Mobile Apk 工程移植。

## 功能

- **UDP 局域网**：端口 2425，GBK，可与飞秋 2013 / 原 WPF 客户端互通
- **WebSocket 中继**：连接同一 RelayService，互联网模式
- 文字消息、文件收发（UDP 走 TCP:2425 拉文件；WS 走 MFQ 分片）
