# Leander 猫猫岛

本项目由 liutongxinhaha666 提出需求并参与实机试用，借助 OpenAI Codex 完成开发与迭代。

一个 Android 悬浮桌宠。平时显示猫猫形象，收到推送时显示消息预览，两分钟后恢复。

- 直接拖动，单击展开消息，双击收起消息或打开 Operit，三击切换形象。
- 播放媒体时切换到耳机猫并摇摆。抖音学习提醒在“调整猫猫岛大小”里授权后生效。
- 在“推送连接设置”填写自己的 HTTPS SSE 地址、订阅令牌和可选的树洞地址。

## 构建

用 Android Studio 打开仓库根目录，安装工程需要的 Android SDK 36 后构建。最低支持 Android 8.0（API 26）。

Windows 也可以运行 `powershell -ExecutionPolicy Bypass -File build-local.ps1`。脚本使用 Android Studio 自带的 JBR 和本地 Android SDK（平台 android-37.0、build-tools 36.0.0），也可用 JAVA_HOME 和 ANDROID_HOME 指定路径。APK 输出到 `build/CatIsland-demo.apk`，使用本机生成的调试签名。

## 推送

`server/` 提供 Python ASGI 实现。部署时设置不同的 ISLAND_READ_TOKEN 和 ISLAND_WRITE_TOKEN，并用 ISLAND_DB 指定可写的 SQLite 路径。可在 HTTPS 反向代理后用 Uvicorn 运行 `standalone:app`，或将 `IslandPushMiddleware` 接到已有 ASGI 服务。独立入口的健康检查也需要设置 ISLAND_TLS_CERT 指向可读的 TLS 证书。

客户端通过 `GET /sse` 订阅，并在 Authorization 请求头发送 Bearer 订阅令牌。发送端通过 `POST /push` 带 Bearer 发送令牌和 JSON 正文，例如 `{"type":"message","text":"消息正文"}`。客户端使用实时模式，只显示连接后的消息。
