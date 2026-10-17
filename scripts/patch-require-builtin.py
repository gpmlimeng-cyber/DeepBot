#!/usr/bin/env python3
"""Android 兼容层：`node-addon-require-builtin` 用纯 JS 回退替换原生加载。

## 为什么要这一层
上游 `node-addon-require-builtin` 通过 `node-addon-native-custom-loader` 加载
**预编译 napi 绑定**，只发布 darwin / linux / win32 平台包：

    optionalDependencies: …-darwin-arm64 / -linux-arm64-gnu / -linux-x64-gnu /
                          -win32-arm64-msvc / -win32-ia32-msvc / -win32-x64-msvc …
    npm view node-addon-require-builtin-android-arm64  →  404

而 `@deepseek-ai/dsh-app-boot` 在**宿主准备阶段**就 require 它并用它访问 Node
内部模块（`internal/modules/esm/loader` 等）。Android 上拿不到绑定 →
`fatal uncaught exception: host preparation failed: No usable native binding found
for node-addon-require-builtin-android-arm64`，引擎根本起不来（2026-10-18 实测）。

## 为什么可以直接走 JS
引擎启动参数本来就带 `--expose-internals`（见 `EngineProcess.kt` 的 node 参数），
所以 `createRequire(__filename)("internal/modules/…")` 能直接拿到同一个内部模块，
与上游 web 侧的 fallback 逻辑一致。**回填了本补丁就必须保留 `--expose-internals`。**

## 幂等
文件里出现 `android-js-fallback` 标记即跳过，可重复执行。
"""
import os
import sys

MARKER = "android-js-fallback"

FALLBACK = '''"use strict";
/** [dsh-android] Android 兼容层：以纯 JS 替代预编译原生 addon。
 *
 * 上游经 node-addon-native-custom-loader 加载原生模块访问 Node 内部模块，
 * 仅 linux-x64 预编译，Android 无法加载。引擎启动已带 --expose-internals
 * （见 app 侧 EngineProcess.kt 的 node 参数），故直接用 createRequire
 * require 内部模块 ID，与上游 web 侧 fallback 逻辑一致。
 */
const { createRequire } = require("node:module");
const req = createRequire(__filename);
function requireBuiltin(moduleId) {
    return req(moduleId);
}
function isAllowedInternalId(moduleId) {
    return typeof moduleId === "string" && moduleId.startsWith("internal/");
}
function getBindingInfo() {
    return { backend: "android-js-fallback", via: "--expose-internals" };
}
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };
'''


def main() -> int:
    raw = os.environ.get("DSH_PATCH_TARGET", "")
    if not raw:
        sys.exit("rejected: DSH_PATCH_TARGET not set by caller")
    root = os.path.realpath(raw)
    path = os.path.join(root, "lib", "node_modules",
                        "node-addon-require-builtin", "lib", "index.js")
    if not os.path.isfile(path):
        print(f"WARN: node-addon-require-builtin not found at {path}")
        return 0
    with open(path, "r", encoding="utf-8") as f:
        cur = f.read()
    if MARKER in cur:
        print(f"already android-js-fallback: {path}")
        return 0
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(FALLBACK)
    print(f"patched android-js-fallback: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
