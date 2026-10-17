#!/usr/bin/env bash
# build-apk.sh —— 一键出包：运行时 → ds-bot → 壳。
#
# 前提：`versions.env` 已锁定引擎版本与 ds-bot commit（那是唯一版本真源）。
# 产物都不入库：runtime.zip 与 ds-bot.zip 由脚本生成，APK 在 app/build/outputs/。
#
# 用法：
#   ./scripts/build-apk.sh                # 三步全跑（runtime 已存在则跳过组装，沿用现有资产）
#   ./scripts/build-apk.sh --rebuild-runtime   # 强制重新组装 runtime.zip
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
. "$ROOT/versions.env"

RUNTIME_ZIP="$ROOT/android/app/src/main/assets/runtime.zip"
REBUILD_RUNTIME=0
[ "${1:-}" = "--rebuild-runtime" ] && REBUILD_RUNTIME=1

echo "== 引擎：$ENGINE_PKG@$ENGINE_VERSION"
echo "== ds-bot：$DSBOT_REPO@${DSBOT_REF:0:12}"

# ① 引擎运行时（Termux 基础层 + 上面锁定的引擎版本 + 全部安卓补丁）
if [ "$REBUILD_RUNTIME" = "1" ] || [ ! -f "$RUNTIME_ZIP" ]; then
  echo "== ① 组装 runtime.zip（较慢，需要网络）"
  ./runtime/collect-termux-runtime.sh "$RUNTIME_ZIP" aarch64
else
  echo "== ① runtime.zip 已存在，跳过（--rebuild-runtime 可强制重建）"
fi
python3 ./runtime/verify-runtime-abi.py "$RUNTIME_ZIP" arm64-v8a

# ② ds-bot：按 versions.env 的 commit 现取现打
echo "== ② 取 ds-bot 并打包"
./scripts/fetch-dsbot.sh

# ③ 壳
echo "== ③ 编译 APK"
./gradlew assembleDebug

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo "== 完成：$APK"
sha256sum "$APK"
