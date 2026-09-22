#!/usr/bin/env python
"""ECharts 图表页 PNG 导出脚本（服务端无头渲染）。

被 Java 侧 ChartPngExporter 通过 ProcessBuilder 调用：
    python export_png.py --url http://127.0.0.1:18290/chart.html?type=daily&days=7 \
        --output /tmp/xxx.png --width 900 --height 480 --timeout 45

流程：打开图表页 → 等待 ECharts CDN 加载与接口数据渲染完成
（window.__chartReady === true，由 chart.html 在 chart.finished 事件里置位）
→ 对 #chart 容器做元素级截屏 → 写 --output 文件。
失败时非零退出，stderr 带原因，Java 侧据此重试或转 503。
"""
import argparse
import sys

from playwright.sync_api import sync_playwright


def main() -> int:
    parser = argparse.ArgumentParser(description="ECharts chart page to PNG via headless Chromium")
    parser.add_argument("--url", required=True, help="图表页完整 URL（含渲染参数）")
    parser.add_argument("--output", required=True, help="PNG 输出文件路径")
    parser.add_argument("--width", type=int, default=900, help="视口宽")
    parser.add_argument("--height", type=int, default=480, help="视口高")
    parser.add_argument("--timeout", type=float, default=45.0, help="单步超时秒数")
    args = parser.parse_args()

    ms = int(args.timeout * 1000)
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            try:
                page = browser.new_page(viewport={"width": args.width, "height": args.height + 40})
                page.goto(args.url, timeout=ms, wait_until="networkidle")
                page.wait_for_function("window.__chartReady === true", timeout=ms)
                # 渲染完成后再给字体/动画半秒缓冲，画面更稳
                page.wait_for_timeout(500)
                element = page.query_selector("#chart")
                if element is None:
                    print("chart container #chart not found", file=sys.stderr)
                    return 3
                element.screenshot(path=args.output, timeout=ms)
            finally:
                browser.close()
    except Exception as exc:  # playwright 超时、页面崩溃等统一归到非零退出
        print(f"export failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
