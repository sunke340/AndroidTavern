#!/usr/bin/env python3
"""生成用于夹具的合成角色卡（PNG + V2 卡 JSON 写进 tEXt chunk）。

为什么要合成卡：默认的 Seraphina 卡带 character_book（内嵌世界书），一生成就会注入
世界书条目，把"卡 → 提示词"这条主干和"世界书激活"（P1）混在同一个夹具里，
失败时分不清是谁的锅。

合成卡的字段用醒目标记（DESC-ALPHA / PERS-BETA / ...），这样原版做了任何变换
（换行归一、宏替换、包装前缀）都能一眼看出来。

用法：
  python3 tools/fixture/make-test-card.py            # 写到 fixtures/cards/P0-5-synthetic.png
"""

import base64
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures/cards/P0-5-synthetic.png"
SOURCE_IMAGE = ROOT / "vendor/SillyTavern/default/content/default_Seraphina.png"

CARD = {
    "spec": "chara_card_v2",
    "spec_version": "2.0",
    "data": {
        "name": "Synthetic",
        "description": "DESC-ALPHA first line\nDESC-ALPHA second line mentions {{user}} and {{char}}",
        "personality": "PERS-BETA",
        "scenario": "SCEN-GAMMA",
        "first_mes": "FIRST-OMEGA",
        "mes_example": "<START>\n{{user}}: EXAMPLE-DELTA-user line\n{{char}}: EXAMPLE-DELTA-char line",
        "creator_notes": "",
        "system_prompt": "",
        "post_history_instructions": "",
        "alternate_greetings": [],
        "tags": [],
        "creator": "androidtavern-fixtures",
        "character_version": "0.0.1",
        "extensions": {},
    },
}


def iter_chunks(data: bytes):
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "不是 PNG"
    i = 8
    while i < len(data):
        length = struct.unpack(">I", data[i : i + 4])[0]
        ctype = data[i + 4 : i + 8]
        payload = data[i + 8 : i + 8 + length]
        yield ctype, payload
        i += 12 + length


def make_chunk(ctype: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + ctype
        + payload
        + struct.pack(">I", zlib.crc32(ctype + payload) & 0xFFFFFFFF)
    )


def main() -> None:
    if not SOURCE_IMAGE.exists():
        raise SystemExit(f"找不到底图：{SOURCE_IMAGE}\n先跑 tools/fixture/bootstrap.sh")

    raw = SOURCE_IMAGE.read_bytes()
    json_bytes = json.dumps(CARD, ensure_ascii=False).encode("utf-8")
    chara = make_chunk(b"tEXt", b"chara\x00" + base64.b64encode(json_bytes))

    out = bytearray(raw[:8])
    for ctype, payload in iter_chunks(raw):
        # 丢掉原有的卡片数据，其余 chunk（IDAT/IHDR/…）原样保留
        if ctype == b"tEXt" and payload.startswith(b"chara\x00"):
            continue
        if ctype == b"tEXt" and payload.startswith(b"ccv3\x00"):
            continue
        out += make_chunk(ctype, payload)
        if ctype == b"IHDR":
            out += chara

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(bytes(out))
    print(f"写好 {OUT.relative_to(ROOT)}（{len(out)} 字节）")


if __name__ == "__main__":
    main()
