#!/usr/bin/env python3
"""
从大量 emoji 素材包中挑出预设的 40 个，复制到目标目录。
支持常见命名：
  1f600.png / 1f600.jpg
  emoji_u1f600.png
  2764-fe0f.png  (带 variation selector)
  2764.png
"""

import os
import shutil
from pathlib import Path

# ========== 改这里 ==========
SRC_DIR = Path(r"C:\Users\Administrator\Downloads\emoji-assets-master\png\64")          # 3~4k 图的源目录
DST_DIR = Path(r"D:\GitHub\[Workspace]\FeiQ2026\WPF\src\FeiQ2026\Assets\Emoji")  # 目标
# ===========================

# 预设 40 个（和代码里一致）
EMOJIS = [
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😜", "🤔", "😎",
    "😢", "😭", "😡", "👍", "👎", "👏", "🙏", "❤️", "💔", "🔥",
    "🎉", "✨", "💯", "✅", "❌", "⭐", "🌟", "💡", "📌", "📎",
    "📷", "🎵", "🎬", "📁", "💻", "📱", "☕", "🍺", "🍕", "🎁",
]

def codepoints(emoji: str) -> list[str]:
    """返回小写 hex codepoint 列表，自动去掉 FE0F"""
    cps = []
    for c in emoji:
        cp = ord(c)
        if cp == 0xFE0F:          # variation selector
            continue
        cps.append(f"{cp:x}")
    return cps

def possible_names(emoji: str) -> set[str]:
    """生成可能的文件名（不含扩展名）"""
    cps = codepoints(emoji)
    if not cps:
        return set()
    names = set()
    # 标准：1f600 / 1f600-1f3fb
    joined = "-".join(cps)
    names.add(joined)
    # 带 fe0f 的版本（有的包会保留）
    if len(emoji) > 1 or any(ord(c) > 0xFFFF for c in emoji):
        names.add(joined + "-fe0f")
        names.add("-".join(cps + ["fe0f"]))
    # Noto 风格
    names.add("emoji_u" + "_".join(cps))
    names.add("emoji_u" + joined.replace("-", "_"))
    # 单 codepoint 时再多试一次
    if len(cps) == 1:
        names.add(cps[0])
        names.add("u" + cps[0])
    return names

def main():
    DST_DIR.mkdir(parents=True, exist_ok=True)

    # 先建立 源文件名(小写无扩展名) -> 完整路径 的索引
    index: dict[str, Path] = {}
    for f in SRC_DIR.rglob("*"):
        if f.is_file() and f.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".gif"}:
            stem = f.stem.lower()
            index[stem] = f
            # 也去掉可能的前缀 emoji_u
            if stem.startswith("emoji_u"):
                index[stem[7:]] = f

    found = 0
    missing = []

    for emoji in EMOJIS:
        candidates = possible_names(emoji)
        hit = None
        for name in candidates:
            if name in index:
                hit = index[name]
                break
            # 模糊：有的包文件名带尺寸 1f600_64 等
            for k, p in index.items():
                if k.startswith(name) or name in k:
                    hit = p
                    break
            if hit:
                break

        if hit:
            # 统一命名为 1f600.png 这种，方便以后代码加载
            cps = codepoints(emoji)
            out_name = "-".join(cps) + hit.suffix.lower()
            dst = DST_DIR / out_name
            shutil.copy2(hit, dst)
            print(f"✅ {emoji}  →  {out_name}  (from {hit.name})")
            found += 1
        else:
            print(f"❌ {emoji}  没找到  尝试过: {sorted(candidates)[:6]}...")
            missing.append(emoji)

    print("\n" + "=" * 40)
    print(f"成功: {found}/{len(EMOJIS)}")
    if missing:
        print("缺失:", " ".join(missing))
        print("把缺失的那几个文件名发我，我帮你补匹配规则。")

if __name__ == "__main__":
    main()