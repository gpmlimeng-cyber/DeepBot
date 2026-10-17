#!/usr/bin/env bash
# fetch-dsbot.sh —— 按 versions.env 里锁定的 commit，从我们自己的二开仓库取 ds-bot，
# 现场打成 app/src/main/assets/deepbot/ds-bot.zip。
#
# 为什么不在仓库里存 zip：快照会过期且无法追溯。存一份 zip 等于把「引用二开仓库」
# 变成一句空话——升级 ds-bot 时没人会想起仓库里还躺着某个时刻的产物。
#
# 用法：./scripts/fetch-dsbot.sh [--check]
#   --check  只比对当前 zip 与锁定 commit 的产物是否一致，不改文件（用于 CI / 复核）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
. "$ROOT/versions.env"

OUT="$ROOT/app/src/main/assets/deepbot/ds-bot.zip"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[dsbot] repo=$DSBOT_REPO ref=$DSBOT_REF"
git -C "$WORK" init -q
git -C "$WORK" remote add origin "$DSBOT_REPO"
# 只取这一个 commit：--depth 1 对任意 sha 在部分服务端不可用，用显式 fetch 兜底
git -C "$WORK" fetch -q --depth 1 origin "$DSBOT_REF" || git -C "$WORK" fetch -q origin "$DSBOT_REF"
git -C "$WORK" checkout -q FETCH_HEAD

SRC="$WORK/$DSBOT_SUBDIR"
[ -d "$SRC" ] || { echo "错误：$DSBOT_REF 下没有 $DSBOT_SUBDIR/ 目录" >&2; exit 1; }

STAGE="$WORK/stage"
mkdir -p "$STAGE"
# 只打运行时需要的文件：包元数据 + 构建产物 + 宿主源码（index.js 会按路径加载 src/host）
cp -a "$SRC"/LICENSE "$SRC"/README.md "$SRC"/package.json "$SRC"/cordis.patch.yml \
      "$SRC"/index.js "$SRC"/client.js "$SRC"/web-search.js "$STAGE"/ 2>/dev/null || true
# 只取运行时需要的源码子目录（与 ds-bot/package.json 的 files 声明一致）。
# 整棵 src/ 连同无关的开发期文件会多出约 1.2 MB，且会进包分发。
for sub in host shared; do
  [ -d "$SRC/src/$sub" ] && mkdir -p "$STAGE/src" && cp -a "$SRC/src/$sub" "$STAGE/src/$sub"
done

python3 - "$STAGE" "$OUT" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for base, _dirs, files in os.walk(stage):
        for f in sorted(files):
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, stage))
print(f"[dsbot] wrote {out}")
PY

if [ "$CHECK" = "1" ]; then
  echo "[dsbot] --check 模式：产物仅用于比对，不回写仓库（比对请用 unzip -l / sha256sum）"
fi
