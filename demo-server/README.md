# demo-server

第一阶段网络化用的电脑本地静态服务器。链路：

```
电脑本地视频目录  →  python http.server  →  (adb reverse)  →  手机 127.0.0.1:8080  →  Retrofit 拉 videos.json  →  Media3 播放网络视频
```

## 一次性准备

把项目根目录 `../视频/` 下的中文名 mp4 转成 ASCII 名（用 ffmpeg `-movflags +faststart` 重排，让 HTTP 能立刻起播），抽每条视频的第一帧做封面，并生成 `videos.json`：

```bash
cd demo-server
python3 prepare.py
```

> 需要 ffmpeg 才能生成封面：`brew install ffmpeg`。没装也能跑，只是没有封面图（App 退化为黑底）。

生成后目录结构：

```
demo-server/
  prepare.py
  videos.json
  videos/
    compatriot.mp4
    kiwi.mp4
    ...
  covers/        # 各视频第一帧 jpg，App 用 Fresco 通过 coverUrl 懒加载
    compatriot.jpg
    ...
```

## 启动服务器

```bash
cd demo-server
python3 -m http.server 8080
```

## 手机连上电脑（USB 调试已开）

```bash
adb reverse tcp:8080 tcp:8080
```

之后 App 启动会自动请求 `http://127.0.0.1:8080/videos.json`。

## 说明

- `videoUrl` / `coverUrl` 都用 ASCII 文件名，URL 无需转义。
- `coverUrl` 指向 `covers/<slug>.jpg`（视频第一帧）。App 用 Fresco 按需加载并缓存：
  没看过的视频露出这张首图，看过的视频用实时截帧覆盖在其上，返回更无缝。
- 换 mp4 时改 `prepare.py` 里的映射表重新生成即可。
