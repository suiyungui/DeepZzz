# camera-web

这是开发板上的 USB 免驱摄像头预览服务，当前是二阶段架构：

```text
USB 摄像头 H.264 输出
  -> ffmpeg 单进程采集并切成 HLS 小片段
  -> Python HTTP 服务发布网页和 HLS 文件
  -> 浏览器用 hls.js 播放
```

## 常用命令

```bash
cd /home/z/camera-web
./start_camera_web.sh
./stop_camera_web.sh
```

访问：

```text
http://192.168.22.193:8080/
```

## 排障顺序

1. 看进程：

```bash
ps -fp $(cat ffmpeg.pid) $(cat web.pid)
```

2. 看端口：

```bash
ss -ltnp | grep ':8080'
```

3. 看 HLS 是否在滚动：

```bash
ls -lh hls
sed -n '1,80p' hls/stream.m3u8
```

4. 看日志：

```bash
tail -n 80 logs/ffmpeg-hls.log logs/web.log
```

## 文件说明

- `index.html`: 浏览器页面。
- `hls.min.js`: 本地 HLS 播放库，避免浏览器依赖公网 CDN。
- `camera_server.py`: 静态 HTTP 服务，发布网页和 `/hls/stream.m3u8`。
- `start_camera_web.sh`: 启动 ffmpeg 和网页服务。
- `stop_camera_web.sh`: 停止 ffmpeg 和网页服务。
- `hls/`: 运行时生成的直播播放列表和 `.ts` 分片。
- `logs/`: 运行日志。

旧 MJPEG 版本已备份为 `*.bak-mjpeg`。
