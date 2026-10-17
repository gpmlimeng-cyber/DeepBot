#!/usr/bin/env python3
"""Android 兼容层：把 `@vscode/ripgrep` 解析出的 `rgPath` 前缀改写为 `/data/user/0/`。

## 为什么需要改写
`@vscode/ripgrep` 用 `require.resolve('@vscode/ripgrep-android-arm64/bin/rg')` 拿路径。
Node 解析模块时会对路径做 **realpath**，App 私有目录因此变成 `/data/data/com.deepbot.app/...`。
而从 `/data/data/` 前缀的路径 **spawn 一律 EACCES**（SELinux 不让我们在那个目录上执行），
表现为文件搜索整体不可用——不是找不到 rg，而是「找得到、执行不了」。

`/data/user/0/...` 是同一个目录的另一条路径（/data/data 是 /data/user/0 的兼容软链），
在那里执行是允许的。

## 为什么不加 `--preserve-symlinks`
那是**全局**开关：会改变所有模块的解析路径（尤其嵌套依赖），可能让同一个包被解析成两份、
状态分裂。为修一个路径去改整套解析语义，后续排查成本远高于这次改写。

## 幂等
出现 `[dsh-android] rgPath` 标记即跳过；已经是 `/data/user/0/` 的路径不会再拼一层。
"""
import os
import sys

MARKER = "[dsh-android] rgPath"
ANCHOR = "export const rgPath = resolved;"

PATCHED = """// [dsh-android] rgPath 前缀改写（幂等）。
// 原因：Node 对 require.resolve 的结果做 realpath，路径变成 /data/data/...；
// 而 /data/data/ 前缀下 spawn 一律 EACCES（SELinux）。/data/user/0/ 是同一目录的
// 另一条路径，可以执行。只改前缀，不动模块解析语义。
const rgFixedPath = String(resolved).startsWith('/data/data/')
    ? '/data/user/0/' + String(resolved).slice('/data/data/'.length)
    : String(resolved);
export const rgPath = rgFixedPath;"""


def main() -> int:
    raw = os.environ.get("DSH_PATCH_TARGET", "")
    if not raw:
        sys.exit("rejected: DSH_PATCH_TARGET not set by caller")
    root = os.path.realpath(raw)
    path = os.path.join(root, "lib", "node_modules", "@vscode", "ripgrep", "lib", "index.js")
    if not os.path.isfile(path):
        print(f"WARN: @vscode/ripgrep not found at {path}")
        return 0
    with open(path, "r", encoding="utf-8") as f:
        cur = f.read()
    if MARKER in cur:
        print(f"already rgPath-rewritten: {path}")
        return 0
    if ANCHOR not in cur:
        print(f"WARN: anchor '{ANCHOR}' not found in {path}; upstream layout changed, manual check needed")
        return 0
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(cur.replace(ANCHOR, PATCHED, 1))
    print(f"patched rgPath prefix: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
