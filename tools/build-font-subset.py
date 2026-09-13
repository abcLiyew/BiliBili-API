#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建内置字体子集（供 render 模块在无界面 Linux 上渲染中文，不依赖系统字体）。

用法:
    python tools/build-font-subset.py <源字体.ttf> <输出.ttf> [--cjk-level gb2312|gbk]

做法:
    1. 若是变体字体（有 fvar 轴），先用 fontTools.varLib.instancer 固定 wght=400
       得到静态字面 —— Java2D 对变体字体的 deriveFont(BOLD) 不生效，静态更可控。
    2. 用 pyftsubset 按字符集裁剪。

字符集 = ASCII + Latin-1/Extended-A + 常用标点 + CJK 标点 + 全角 + 假名 + GB2312/GBK 汉字。
GB2312 含 6763 个汉字（一级+二级），对"动态正文"场景足够；GBK 覆盖 20902 字但体积大很多。
"""
import argparse
import os
import subprocess
import sys
import tempfile


def build_charset(cjk_level: str) -> set:
    chars = set()

    # ASCII 可见字符
    chars.update(chr(cp) for cp in range(0x20, 0x7F))
    # Latin-1 Supplement + Latin Extended-A（西欧重音字母）
    chars.update(chr(cp) for cp in range(0xA0, 0x180))
    # General Punctuation（—…""''•‰等）
    chars.update(chr(cp) for cp in range(0x2000, 0x2070))
    # Superscripts / Subscripts / Currency / Letterlike / Arrows / Math / Misc
    chars.update(chr(cp) for cp in range(0x2070, 0x2100))
    chars.update(chr(cp) for cp in range(0x2190, 0x2200))
    chars.update(chr(cp) for cp in range(0x2200, 0x2300))
    chars.update(chr(cp) for cp in range(0x2460, 0x2500))
    # CJK 符号与标点（、。「」【】〜等）
    chars.update(chr(cp) for cp in range(0x3000, 0x3040))
    # 平假名 / 片假名
    chars.update(chr(cp) for cp in range(0x3040, 0x3100))
    # 全角字符
    chars.update(chr(cp) for cp in range(0xFF00, 0xFFF0))

    # 汉字：按编码集取
    enc = "gb2312" if cjk_level == "gb2312" else "gbk"
    for hi in range(0xA1, 0xFF):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode(enc))
            except UnicodeDecodeError:
                pass
    # 单字节区（GBK 扩展的 0x80-0xFE 单字节）
    if cjk_level == "gbk":
        for b in range(0x80, 0x100):
            try:
                chars.add(bytes([b]).decode("gbk"))
            except UnicodeDecodeError:
                pass

    # 常见但不在上述区间的符号，兜一手
    chars.update("★☆●○◆◇■□▲▼△▽※→←↑↓↔♥♡✓✔✗✘✿❀•·–—…‘’“”《》〈〉「」『』【】〔〕～￥€£¥°′″№§¶†‡±×÷≈≠≤≥∞∴∵∈∉√∝∑∏∫µ√")
    chars.discard("\n")
    chars.discard("\r")
    return chars


def has_variable_axis(src: str) -> bool:
    from fontTools.ttLib import TTFont

    with TTFont(src, lazy=True) as f:
        return "fvar" in f


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("output")
    ap.add_argument("--cjk-level", choices=["gb2312", "gbk"], default="gb2312")
    args = ap.parse_args()

    src = os.path.abspath(args.source)
    out = os.path.abspath(args.output)
    if not os.path.isfile(src):
        print("源字体不存在: %s" % src, file=sys.stderr)
        return 2
    os.makedirs(os.path.dirname(out), exist_ok=True)

    tmpdir = tempfile.mkdtemp(prefix="fontsubset-")
    work = src

    # 1) 变体字体 → 固定 wght=400 的静态字面
    try:
        if has_variable_axis(src):
            inst = os.path.join(tmpdir, "instanced.ttf")
            print("[1/3] 变体字体，固定 wght=400 → %s" % os.path.basename(inst))
            subprocess.run(
                [sys.executable, "-m", "fontTools.varLib.instancer", src, "wght=400", "-o", inst],
                check=True,
            )
            work = inst
        else:
            print("[1/3] 静态字体，跳过 instancer")
    except subprocess.CalledProcessError as e:
        print("instancer 失败: %s" % e, file=sys.stderr)
        return 3

    # 2) 生成字符集
    chars = build_charset(args.cjk_level)
    text_file = os.path.join(tmpdir, "chars.txt")
    with open(text_file, "w", encoding="utf-8") as f:
        f.write("".join(sorted(chars)))
    print("[2/3] 字符集 %d 个码位（%s）" % (len(chars), args.cjk_level))

    # 3) 裁剪
    print("[3/3] pyftsubset → %s" % out)
    subprocess.run(
        [
            sys.executable, "-m", "fontTools.subset", work,
            "--text-file=" + text_file,
            "--output-file=" + out,
            "--layout-features=*",
            "--no-hinting",
            "--desubroutinize",
            "--drop-tables+=DSIG",
            "--name-IDs=*",
            "--recalc-bounds",
        ],
        check=True,
    )

    src_kb = os.path.getsize(src) / 1024
    out_kb = os.path.getsize(out) / 1024
    print("完成：%.1f KB → %.1f KB（%.0f%%）" % (src_kb, out_kb, out_kb / src_kb * 100))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
