# Maqiu

局域网低延迟安卓投屏到 Windows 的完整工程实现，包含 Android Studio 工程与 Windows CMake 工程。实现使用 MediaProjection + MediaCodec 硬编码 H264，并通过 RTP/UDP 在局域网传输，Windows 端使用 FFmpeg 解码并通过 SDL2 实时显示。

## 目录结构

```
Maqiu/
  android/          Android Studio 工程 (Java)
  windows/          Windows CMake 工程 (receiver.exe)
```

## Android 端（发送端）

### 功能概览
- MediaProjection 录屏
- MediaCodec Surface 输入硬编码 H264（Baseline Profile）
- 90kHz RTP 时间戳体系
- RTP 封包（支持 FU-A 分片，单包 <= 1400 字节）
- 带上限的 FrameQueue（丢弃旧帧保证低延迟）
- BufferPool 复用内存
- 简单 ABR 自适应码率（基于丢包反馈）
- UDP 发送节流 pacing
- UDP 广播设备发现响应

### 编译与运行
1. 使用 Android Studio 打开 `android/` 目录。
2. 连接 Android 设备并运行应用。
3. 点击 **Grant Screen Capture Permission** 授权录屏。
4. 点击 **Start Cast Service** 启动前台服务，等待 Windows 端连接。

## Windows 端（接收端）

### 功能概览
- UDP 接收（WinSock）
- RTP 解析（序号、时间戳、Marker 位）
- Jitter Buffer（乱序重排、滑动窗口、丢包检测）
- 丢包反馈（LOSS|percent）
- FFmpeg H264 解码（处理 EAGAIN）
- 解码线程与渲染线程分离
- SDL2 实时 YUV 渲染
- Win32 GUI（刷新设备/连接/停止）
- 设备发现与响应

### 编译与运行
1. 安装 FFmpeg 与 SDL2（推荐解压到本地路径）。
2. 生成工程（示例）：
   ```
   cmake -S windows -B windows/build -DFFMPEG_ROOT=C:/ffmpeg -DSDL2_ROOT=C:/SDL2
   cmake --build windows/build --config Release
   ```
3. 运行 `windows/build/Release/receiver.exe`。
4. 点击 **Refresh** 发现设备，选择设备后点击 **Connect**。

## 协议与端口
- 发现端口：50000（UDP 广播）
- 控制端口：50001（UDP）
- RTP 端口：50002（UDP）
- 丢包反馈端口：50003（UDP）

### 关键消息
- 发现请求：`MAQIU_DISCOVER`
- 发现响应：`MAQIU_DEVICE|<name>|<rtp_port>|<feedback_port>|<control_port>`
- 启动命令：`MAQIU_START|<receiver_ip>|<rtp_port>|<feedback_port>`
- 停止命令：`MAQIU_STOP`

## 关键说明
- RTP 使用标准 12 字节头，时间戳 90kHz。
- H264 为 Baseline Profile，无 B 帧，GOP <= 1s，固定 60fps。
- Android 端 FrameQueue 保持 5~10 帧，Windows 端 Jitter Buffer 至少缓存 3~5 帧。
- 乱序与丢包可容忍，关键帧丢失后会等待下一个 IDR 恢复。

## 运行建议
- 确保 Android 与 Windows 在同一局域网内。
- Windows 防火墙允许 UDP 端口。
- 1080p/60fps 建议使用 5GHz Wi-Fi 或有线网络。
