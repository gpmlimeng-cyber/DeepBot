#!/usr/bin/env python3
"""Android 兼容层：把 `dsh-client-ui-layout` 的收起态侧栏宽度 `collapsedWidth` 改成 0。

## 为什么
引擎的列宽不是 CSS 给的，而是写在 frame 的**内联** `grid-template-columns` 上
（`client.js` 里 `${cols.sidebar}px minmax(…) minmax(…)`），收起态第一条轨道就是：

    const collapsedWidth = darwin || data-windows-titlebar ? 0 : 56;   // ≈ :242

手机竖屏下这条 56px 是常驻的（点它展开会话列表），但它同时把对话区挤窄 56px。
实测在 WebView 里用 CSS/JS 压它无效：`!important` 压不过 React 写在元素上的
内联 `!important`，且样式表在我们的 `<style>` 之后加载时同权重后者胜
（2026-10-18 连续三版注入规则验证：`sidebarCol` 恒为 56、`centerCol` 恒从 56 起）。

## 做法
把三元里的 56 改成 0，即 Android 上不存在收起态轨道。
**这个 runtime 只为 Android APK 构建**（`collect-termux-runtime.sh` 产出的就是
arm64 安卓树），桌面端需要的图标导轨不存在于本产物中，所以无需平台判断；
将来若用同一脚本产出桌面运行时，必须回来重新审视这一行。

## 幂等
出现 `[dsh-android] collapsedWidth` 标记即跳过。
"""
import os
import sys

MARKER = "[dsh-android] collapsedWidth"
ANCHOR = "const collapsedWidth = darwin || document.documentElement.hasAttribute(\"data-windows-titlebar\") ? 0 : 56;"

PATCHED = ("// [dsh-android] collapsedWidth=0：手机竖屏常驻 56px 轨道会挤窄对话区，\n"
           "\t\t\t// 且该轨道是内联 grid 模板的第一条，CSS/JS 覆盖无效（实测三版）。\n"
           "\t\t\t// 本 runtime 只为 Android APK 构建，桌面图标导轨不存在，故直接取 0。\n"
           "\t\t\tconst collapsedWidth = 0;")


def main() -> int:
    raw = os.environ.get("DSH_PATCH_TARGET", "")
    if not raw:
        sys.exit("rejected: DSH_PATCH_TARGET not set by caller")
    root = os.path.realpath(raw)
    path = os.path.join(root, "lib", "node_modules", "@deepseek-ai",
                        "dsh-client-ui-layout", "lib", "client.js")
    if not os.path.isfile(path):
        print(f"WARN: dsh-client-ui-layout not found at {path}")
        return 0
    with open(path, "r", encoding="utf-8") as f:
        cur = f.read()
    if MARKER in cur:
        print(f"already collapsedWidth=0: {path}")
        return 0
    if ANCHOR not in cur:
        print(f"WARN: anchor for collapsedWidth not found in {path}; upstream changed, manual check needed")
        return 0
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(cur.replace(ANCHOR, PATCHED, 1))
    print(f"patched collapsedWidth=0: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
