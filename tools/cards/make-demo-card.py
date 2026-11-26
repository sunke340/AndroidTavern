#!/usr/bin/env python3
"""生成随包演示卡（`app/src/main/assets/cards/*.png`）。

为什么不用别人的卡：SillyTavern 默认内容（`default_Seraphina.png`）的内容版权归原作者，
官方内容仓库明确写了「再分发需自行取得许可」，随 APK 分发就是再分发。
所以这东西必须是我们自己的：**卡面用代码画、正文自己写、脚本可重跑** —— 没有不可复现的输入。

产物是一个普通 PNG：
- 图像：768x1024（卡位是 3:4 满幅），RGB8、非隔行，只用 IHDR/IDAT/IEND 三个块；
- 文本：`chara`（V2 外层 JSON）与 `ccv3`（同一份 data，标成 `chara_card_v3`/`3.0`），
  两个 `tEXt` 块都放 **base64**（`tEXt` 只能放 latin-1，base64 是 ASCII，最稳）。
  这正是 `PngCard.write()` 的写法：读的时候 `ccv3` 优先、没有再退回 `chara`。

用法：
    python3 tools/cards/make-demo-card.py                    # 写默认产物
    python3 tools/cards/make-demo-card.py --out /tmp/x.png   # 换个位置（比对用）

改了卡面或正文就重跑一遍，把产物一起提交 —— 别手改 PNG。
"""

import argparse
import base64
import json
import math
import struct
import zlib

# 卡面配色：深靛蓝的雨夜 → 灯下的暖光。改这里就是改卡面。
TOP = (18, 20, 34)        # 上方：雨夜的靛蓝
BOTTOM = (40, 30, 26)     # 下方：旧木与纸的暗褐（卡名压在这条上，别太亮）
GLOW = (255, 178, 102)    # 灯光的暖黄
GLOW_Y = 0.34             # 灯在画面的纵向位置（0 = 顶）
GLOW_R = 0.17             # 灯的"光池"半径（占短边比例）


def gradient_image(width: int, height: int) -> bytes:
    """画卡面：竖直渐变 + 一团灯 + 连续衰减的光晕 + 暗角 + 抖动。

    光晕**必须是距离的连续函数**：上一版按"半径内 0、半径外才有光晕"分开算，
    结果灯的正圆边界上出现一圈暗环（内侧没光晕、外侧有），像贴了个黑盘子。
    """
    px = bytearray(width * height * 3)
    cx, cy = width * 0.5, height * GLOW_Y
    r_glow = min(width, height) * GLOW_R
    i = 0
    for y in range(height):
        t = y / (height - 1)
        # 竖直渐变：用平方根让暖色集中在下半部分（线性会让上半截发灰）
        tt = math.sqrt(t)
        base_r = TOP[0] + (BOTTOM[0] - TOP[0]) * tt
        base_g = TOP[1] + (BOTTOM[1] - TOP[1]) * tt
        base_b = TOP[2] + (BOTTOM[2] - TOP[2]) * tt
        for x in range(width):
            dx, dy = x - cx, y - cy
            dist = math.hypot(dx, dy)
            # 光池：平方反比型，从灯心连续衰减到 0（没有分支，就没有边界）
            pool = 0.88 / (1.0 + (dist / (r_glow * 1.15)) ** 2) ** 1.35
            # 灯芯：一小团更亮的高斯，让"灯"这件事读得出来
            core = 1.02 * math.exp(-((dist / (r_glow * 0.5)) ** 2))
            # 暗角：四角压暗，视线留在中间
            vx, vy = x / width - 0.5, y / height - 0.5
            vig = 1.0 - 0.62 * (vx * vx + vy * vy)
            light = (pool + core) * vig
            # 确定性抖动（±1.5 级）：8bit 渐变不加抖动会出肉眼可见的色带
            n = (((x * 1103515245 + y * 12345) >> 7) & 0x7F) / 128.0 - 0.5
            r = base_r * vig + GLOW[0] * light + n * 3
            g = base_g * vig + GLOW[1] * light + n * 3
            b = base_b * vig + GLOW[2] * light + n * 3
            px[i] = _clamp(r)
            px[i + 1] = _clamp(g)
            px[i + 2] = _clamp(b)
            i += 3
    return bytes(px)


def _clamp(v: float) -> int:
    return 0 if v < 0 else 255 if v > 255 else int(v)


def encode_png(width: int, height: int, rgb: bytes) -> bytes:
    """最小 PNG 编码器：RGB8、每行 filter=0。够用就好，不引 Pillow。"""
    raw = bytearray()
    stride = width * 3
    for y in range(height):
        raw.append(0)  # filter type 0 (None)
        raw += rgb[y * stride:(y + 1) * stride]

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8bit truecolor
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def text_chunk(keyword: str, text: str) -> bytes:
    body = keyword.encode("latin-1") + b"\x00" + text.encode("latin-1")
    return (
        struct.pack(">I", len(body))
        + b"tEXt"
        + body
        + struct.pack(">I", zlib.crc32(b"tEXt" + body) & 0xFFFFFFFF)
    )


# ── 卡正文 ────────────────────────────────────────────────────────────────
# 内容自己写。刻意覆盖几种能力，让首启那张卡本身就能当"能跑"的证明：
# 多条开场白（‹1/3› 能翻）、内嵌世界书两条（关键字命中才注入）、示例对话（mes_example）。
CARD = {
    "spec": "chara_card_v2",
    "spec_version": "2.0",
    "data": {
        "name": "青禾",
        "description": "山脚书铺的掌柜。书铺叫「留白」，只卖旧书，也让人坐着看。"
        "她记得每个来过的人的名字，也记得他们借走了哪一本。",
        "personality": "温和、话不多、记得住细节。被夸会不好意思，转身去整理书架。"
        "下雨天会多烧一壶水，说是给路上的人留的。",
        "scenario": "雨天的午后，你推门进了这间书铺。檐水顺着瓦当往下滴，"
        "屋里是旧纸和松木的味道，灯芯挑得不高。",
        "first_mes": "*门轴响了一声。她抬头，把手里的书翻扣在桌上。*\n\n"
        "「外面雨大，先坐吧。」*她往炉边推了推一张矮凳。*「要找书，还是躲雨？」",
        "mes_example": "<START>\n"
        "{{user}}: 这本为什么没有名字？\n"
        "{{char}}: *她把书脊转过来给你看。*「有的。被人摸得太多，磨掉了。」\n"
        "<START>\n"
        "{{user}}: 你记得我上次借了什么？\n"
        "{{char}}: 「记得。」*她从抽屉里抽出一张纸条，上面是你自己写的名字。*"
        "「还没还。」",
        "creator": "AndroidTavern",
        "character_version": "1.0.0",
        "tags": ["日常", "治愈"],
        "alternate_greetings": [
            "*她从梯子上下来，怀里抱着三本书，看见你愣了一下。*\n\n"
            "「你来得正好——上次那本，有下文了。」*她把最上面一本递过来，"
            "指尖还沾着灰。*",
            "*灯芯挑得有点高，屋里亮得发白。她正把一叠写满字的纸压在镇纸下面。*\n\n"
            "「……你什么都没看见，对吧？」*说完自己先笑了。*",
        ],
        "character_book": {
            "name": "留白书铺",
            "entries": [
                {
                    "id": 0,
                    "keys": ["书铺", "留白", "书架"],
                    "secondary_keys": [],
                    "comment": "书铺的格局",
                    "content": "留白书铺一共七排书架，按「朝代」排，不按作者。"
                    "第三排最里侧常年空着一格，她说那是留给「还没写完的书」的。",
                    "constant": False,
                    "selective": False,
                    "insertion_order": 100,
                    "enabled": True,
                    "position": "before_char",
                    "case_sensitive": False,
                    "name": "书铺的格局",
                    "priority": 10,
                    "extensions": {},
                },
                {
                    "id": 1,
                    "keys": ["雨", "茶", "炉子"],
                    "secondary_keys": [],
                    "comment": "雨天与茶",
                    "content": "下雨天她会多烧一壶水，茶叶是最便宜的那种，"
                    "她说「雨声一响，贵茶和便宜茶一个味」。",
                    "constant": False,
                    "selective": False,
                    "insertion_order": 100,
                    "enabled": True,
                    "position": "before_char",
                    "case_sensitive": False,
                    "name": "雨天与茶",
                    "priority": 10,
                    "extensions": {},
                },
            ],
            "extensions": {},
        },
        "extensions": {},
    },
}


def build_card_png(width: int = 768, height: int = 1024) -> bytes:
    image = encode_png(width, height, gradient_image(width, height))
    v2 = json.dumps(CARD, ensure_ascii=False, separators=(",", ":"))
    v3_obj = dict(CARD)
    v3_obj["spec"] = "chara_card_v3"
    v3_obj["spec_version"] = "3.0"
    v3 = json.dumps(v3_obj, ensure_ascii=False, separators=(",", ":"))

    chara = base64.b64encode(v2.encode("utf-8")).decode("ascii")
    ccv3 = base64.b64encode(v3.encode("utf-8")).decode("ascii")

    # 两个 tEXt 块插在 IEND 之前（和 PngCard.write 的位置一致）
    head, tail = image[:-12], image[-12:]  # tail = IEND 整个块
    assert tail[4:8] == b"IEND", "编码器产出的最后一个块不是 IEND"
    return head + text_chunk("chara", chara) + text_chunk("ccv3", ccv3) + tail


def main() -> None:
    ap = argparse.ArgumentParser(description="生成随包演示卡 PNG")
    ap.add_argument(
        "--out",
        default="app/src/main/assets/cards/qinghe.png",
        help="输出路径（默认写进 assets/cards/）",
    )
    args = ap.parse_args()
    data = build_card_png()
    with open(args.out, "wb") as f:
        f.write(data)
    print(f"写出 {args.out}（{len(data)} 字节）")


if __name__ == "__main__":
    main()
