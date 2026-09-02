# -*- coding: utf-8 -*-
"""
extract_mermaid.py —— 从《设计类.md》提取全部 mermaid 类图并渲染为 PNG 图片。

用法：
    python extract_mermaid.py

实现说明：
- 用正则按顺序提取 ```mermaid 代码块，并以最近的 "## " 标题作为图片命名依据；
- 经 https://mermaid.ink 渲染：将 mermaid 源码按 UTF-8 编码后做 URL-safe Base64，
  拼接为 https://mermaid.ink/img/{encoded}?type=png（经探针验证的可用格式；
  pako/deflate 压缩格式会被服务端以 400 拒绝）；
- 仅依赖 Python 标准库（urllib/base64），无需安装第三方包；
- 网络异常时最多重试 1 次，仍失败则以非零码退出（由调用方介入处理）。

@author zhanjh
@since 0.0.1
"""
import base64
import re
import sys
import time
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(r"C:\Users\ZhanJH\Desktop\MyProject\Hercules")
SRC = ROOT / "设计类.md"
OUT = ROOT / "开发文档" / "images"
OUT.mkdir(parents=True, exist_ok=True)


def extract_blocks(text: str):
    """按出现顺序返回 [(所属二级标题, mermaid 代码), ...]。"""
    blocks = []
    for m in re.finditer(r"```mermaid\s*\n(.*?)```", text, re.S):
        heads = re.findall(r"^##\s+(.+?)\s*$", text[: m.start()], re.M)
        title = heads[-1] if heads else "未命名图"
        blocks.append((title, m.group(1).strip()))
    return blocks


def safe_name(title: str) -> str:
    """把标题转成 Windows 合法文件名片段。"""
    for ch in '：:（）()*/\\?*"<>|、，　 ':
        title = title.replace(ch, "")
    return title


def render_png(code: str) -> bytes:
    """调用 mermaid.ink 渲染；最多尝试 2 次，失败抛出最后一次异常。"""
    encoded = base64.urlsafe_b64encode(code.encode("utf-8")).decode("ascii")
    url = f"https://mermaid.ink/img/{encoded}?type=png"
    last_err = None
    for attempt in (1, 2):
        try:
            req = urllib.request.Request(
                url, headers={"User-Agent": "Mozilla/5.0 hercules-doc-gen/1.0"})
            with urllib.request.urlopen(req, timeout=90) as resp:
                data = resp.read()
            if resp.status == 200 and len(data) > 1024:
                return data
            last_err = RuntimeError(f"HTTP {resp.status}, {len(data)} bytes")
        except Exception as exc:  # 网络/服务端瞬时异常：记录后短候重试一次
            last_err = exc
            time.sleep(3)
    raise RuntimeError(f"渲染失败（共 2 次尝试）: {last_err}")


def main() -> int:
    text = SRC.read_text(encoding="utf-8")
    blocks = extract_blocks(text)
    print(f"共发现 {len(blocks)} 个 mermaid 代码块")

    for idx, (title, code) in enumerate(blocks, 1):
        name = f"{idx:02d}-{safe_name(title)}.png"
        try:
            data = render_png(code)
        except Exception as exc:
            print(f"FAILED {name}: {exc}")
            return 2
        (OUT / name).write_bytes(data)
        print(f"OK     {name}  ({len(data) / 1024:.1f} KB)  <- {title}")

    print(f"完成：{len(blocks)} 张图片已保存到 {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
