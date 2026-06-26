#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键准备本地静态服务器内容：
  1) 把项目根目录 ../视频/ 下的中文名 mp4 复制成 videos/ 下的 ASCII 名（URL 不必转义）；
  2) 生成 videos.json（频道结构与原 Demo 一致）。

用法（在 demo-server/ 目录下）：
    python3 prepare.py
    python3 -m http.server 8080

手机连上电脑后：
    adb reverse tcp:8080 tcp:8080
App 启动即请求 http://127.0.0.1:8080/videos.json
"""

import json
import os
import shutil
import subprocess

BASE_URL = "http://127.0.0.1:8080"

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE_DIR = os.path.join(HERE, "..", "视频")
VIDEOS_DIR = os.path.join(HERE, "videos")
COVERS_DIR = os.path.join(HERE, "covers")

# 抽首帧做封面需要 ffmpeg；没装也能跑，只是没有封面图（App 退化为黑底）。
HAS_FFMPEG = shutil.which("ffmpeg") is not None

# (中文源文件, ASCII 文件名, 标题, 作者, 互动数据)
FEATURED = [
    ("我们是同胞.mp4", "compatriot.mp4", "我们是同胞", "@daily_watch", "1.8w likes  ·  428 comments"),
    ("猕猴桃.mp4", "kiwi.mp4", "猕猴桃", "@fruit_note", "6,204 likes  ·  88 comments"),
    ("巨构.mp4", "megastructure.mp4", "巨构", "@city_scale", "9,821 likes  ·  156 comments"),
    ("麦浪过来咿呀咿呀.mp4", "wheat_wave.mp4", "麦浪过来咿呀咿呀", "@field_song", "2.4w likes  ·  391 comments"),
]
EXPERIENCE = [
    ("我们手牵手云海下约好.mp4", "cloud_hands.mp4", "我们手牵手云海下约好", "@cloud_walk", "8,176 likes  ·  221 comments"),
    ("R&B便利贴.mp4", "rnb_note.mp4", "R&B 便利贴", "@music_memo", "1.1w likes  ·  312 comments"),
    ("我就这样看讨厌的人.mp4", "disliked_people.mp4", "我就这样看讨厌的人", "@small_mood", "7,604 likes  ·  108 comments"),
]
HOT = [
    ("我枣糕世界！.mp4", "date_cake_world.mp4", "我枣糕世界！", "@hot_today", "12.4w likes  ·  2,901 comments"),
    ("人心呐.mp4", "human_heart.mp4", "人心呐", "@trend_watch", "7.6w likes  ·  1,406 comments"),
    ("一天一天贴近你的心.mp4", "closer_to_your_heart.mp4", "一天一天贴近你的心", "@quick_news", "5.2w likes  ·  984 comments"),
    ("范玮琪开口低音.mp4", "fan_weiqi_low_note.mp4", "范玮琪开口低音", "@music_clip", "9.3w likes  ·  1,702 comments"),
]
LIVE = [
    ("WorldWar3.mp4", "world_war_3.mp4", "World War 3", "@timeline_notes", "4,321 likes  ·  73 comments"),
    ("直播·安详.mp4", "live_peaceful.mp4", "安详", "@live_room", "2.1w likes  ·  1,032 comments"),
    ("直播·iPhone手机.mp4", "live_iphone.mp4", "iPhone 手机", "@maker_live", "9,405 likes  ·  642 comments"),
    ("直播·成语接龙.mp4", "live_idiom_game.mp4", "成语接龙", "@game_live", "4.8w likes  ·  3,215 comments"),
]
FOLLOWING = [
    ("优雅步伐.mp4", "elegant_steps.mp4", "优雅步伐", "@friend_video", "3,502 likes  ·  66 comments"),
    ("烈日永照.mp4", "endless_sun.mp4", "烈日永照", "@study_daily", "2,908 likes  ·  45 comments"),
    ("草原烂麦.mp4", "grassland_wheat.mp4", "草原烂麦", "@code_diary", "5,601 likes  ·  118 comments"),
]
LOCAL = [
    ("黄龙江一带全部带蓝牙.mp4", "yellow_dragon_bluetooth.mp4", "黄龙江一带全部带蓝牙", "@city_walk", "1.2w likes  ·  278 comments"),
    ("怎样你们才能放过我.mp4", "let_me_go.mp4", "怎样你们才能放过我", "@local_food", "2.7w likes  ·  604 comments"),
    ("旋转几轮变成我们深刻的指纹.mp4", "fingerprint_spin.mp4", "旋转几轮变成我们深刻的指纹", "@nearby_view", "7,889 likes  ·  139 comments"),
]
RECOMMENDED = [
    ("穿搭.mp4", "outfit.mp4", "今日穿搭灵感", "@style_daily", "3.2w likes  ·  910 comments"),
    ("白鲸吐泡泡.mp4", "beluga_bubbles.mp4", "白鲸吐泡泡", "@ocean_diary", "6.8w likes  ·  1,508 comments"),
    ("水母.mp4", "jellyfish.mp4", "水母慢慢游", "@jellyfish_view", "9.9w likes  ·  2,345 comments"),
]

CHANNELS = [
    ("精选", FEATURED, False),
    ("经验", EXPERIENCE, False),
    ("热点", HOT, False),
    ("直播", LIVE, True),
    ("关注", FOLLOWING, False),
    ("同城", LOCAL, False),
    ("推荐", RECOMMENDED, False),
]


def video_item(item):
    _src, ascii_name, title, author, stats = item
    slug = os.path.splitext(ascii_name)[0]
    return {
        "title": title,
        "author": author,
        "stats": stats,
        "videoUrl": f"{BASE_URL}/videos/{ascii_name}",
        # 这一版 App 暂不加载网络封面（先黑底 / 实时截帧），coverUrl 仅占位，后续接 Fresco 再用。
        "coverUrl": f"{BASE_URL}/covers/{slug}.jpg",
    }


def remux_faststart(src_path, dst_path):
    # 关键：把 moov 原子挪到文件头（+faststart），HTTP 渐进式流式才能立刻起播；
    # 否则 ExoPlayer 要先把文件尾拉下来找 moov，表现为"请求了 mp4 却迟迟不播"。
    # -c copy 只重排容器、不重新编码，很快。
    subprocess.run(
        ["ffmpeg", "-y", "-i", src_path, "-c", "copy", "-movflags", "+faststart", dst_path],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=True,
    )


def generate_cover(video_path, cover_path):
    # 抽视频第一帧存成 jpg 作为封面首图（-frames:v 1 取一帧，-q:v 2 质量较高）。
    subprocess.run(
        ["ffmpeg", "-y", "-i", video_path, "-frames:v", "1", "-q:v", "2", cover_path],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=True,
    )


def main():
    os.makedirs(VIDEOS_DIR, exist_ok=True)
    os.makedirs(COVERS_DIR, exist_ok=True)

    missing = []
    for _title, items, _live in CHANNELS:
        for item in items:
            src_name = item[0]
            ascii_name = item[1]
            src_path = os.path.join(SOURCE_DIR, src_name)
            dst_path = os.path.join(VIDEOS_DIR, ascii_name)
            if not os.path.exists(src_path):
                missing.append(src_name)
                continue
            if HAS_FFMPEG:
                # 重排为 faststart 以便流式起播，并抽首帧做封面。
                remux_faststart(src_path, dst_path)
                slug = os.path.splitext(ascii_name)[0]
                generate_cover(dst_path, os.path.join(COVERS_DIR, f"{slug}.jpg"))
            else:
                # 没装 ffmpeg：只能原样复制（可能不是 faststart，网络起播会偏慢）。
                shutil.copyfile(src_path, dst_path)

    feed = {
        "channels": [
            {
                "title": title,
                "isLiveChannel": is_live,
                "videoItems": [video_item(i) for i in items],
            }
            for title, items, is_live in CHANNELS
        ]
    }
    with open(os.path.join(HERE, "videos.json"), "w", encoding="utf-8") as f:
        json.dump(feed, f, ensure_ascii=False, indent=2)

    copied = sum(len(items) for _t, items, _l in CHANNELS) - len(missing)
    if HAS_FFMPEG:
        print(f"已重排 {copied} 个视频(faststart)到 {VIDEOS_DIR}")
        print(f"已用 ffmpeg 抽首帧生成封面到 {COVERS_DIR}")
    else:
        print(f"已复制 {copied} 个视频到 {VIDEOS_DIR}")
        print("未检测到 ffmpeg：未做 faststart 重排、未生成封面（网络起播会偏慢、封面退化为黑底）。安装：brew install ffmpeg")
    print(f"已生成 {os.path.join(HERE, 'videos.json')}")
    if missing:
        print("以下源文件缺失（请检查 ../视频/ 下文件名）：")
        for name in missing:
            print("  -", name)


if __name__ == "__main__":
    main()
