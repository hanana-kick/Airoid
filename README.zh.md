<p align="center">
  <img src="docs/icon.png" width="160" height="160" alt="Airoid" />
</p>

<p align="center">
  <a href="README.md">한국어</a> · <a href="README.en.md">English</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh.md">中文</a>
</p>

# Airoid

把 Android 设备变成 AirPlay 接收器的应用。将 Mac 或 iPhone 的画面无线镜像到
Android 设备上当作副屏使用，还可以播放 AirPlay 音频。

## 主要功能

- **屏幕镜像** — H.264/H.265 硬件解码，低延迟管线
- **音频接收** — AAC（硬件）、ALAC（无硬件时软件回退）
- **过渡动画** — 连接/结束时设备边框（圆环）放大缩小，画面淡入淡出
- **快速设置磁贴** — 显示镜像状态（ACTIVE/INACTIVE），点击启动应用
- **多语言界面** — 한국어/English/日本語/中文(简体)，支持应用级语言设置
- **配对码** — 以各语言文学作品标题标识设备（例如：`Airoid 三国演义`）

## 安装

系统要求：**Android 8.0 以上**，ARM64 设备

1. 从以下发布页面下载最新的 `app-release.apk`。
   - GitHub: <https://github.com/hanana-kick/Airoid/releases/latest>
2. 打开下载的 APK。如果出现“安装未知来源应用”的警告，请为下载所用的浏览器或
   文件管理器授予安装权限。
3. 安装完成后启动应用。

以后更新时，同样用新 APK 覆盖安装即可（数据会保留）。

## 使用方法

1. 启动应用后，待机画面会显示配对码（例如：`Airoid 三国演义`）。
2. 在 Mac 的 AirPlay 菜单中选择该设备名称，即可开始镜像。
3. 镜像过程中向上滑动，会打开“结束镜像”面板。
4. （可选）在快速设置的编辑模式中添加“Airoid”磁贴，即可查看状态并点击启动应用。

## 许可证

第三方组件（UxPlay、FFmpeg、libplist、openssl、oboe）遵循各自的开源许可证。
