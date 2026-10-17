# 构建与依赖（DeepBot 二开）

本仓库是 DeepBot 的**壳**（Android 工程）。引擎与 ds-bot 都不在这里，各自由
`versions.env` 锁定版本/commit，构建时取用。**任何"某版本的快照"都不入库。**

## 三条依赖分别锁在哪

| 依赖 | 来源 | 锁定方式 | 落在哪 |
|---|---|---|---|
| 引擎 | npm `@deepseek-ai/dsh`（官方上游） | `versions.env` 的 `ENGINE_VERSION` + `ENGINE_INTEGRITY`(sha512) | 由 `scripts/collect-termux-runtime.sh` 组装进 `app/src/main/assets/runtime.zip`（不入库） |
| 引擎运行时 | Termux 仓库 + 上面的引擎 | 同上，产物指纹记 `RUNTIME_ASSET_SHA256` | 同上 |
| ds-bot | `gpmlimeng-cyber/dsh-bot`（我们的二开仓库） | `versions.env` 的 `DSBOT_REF`（commit sha，不用分支） | `scripts/fetch-dsbot.sh` 现取现打 → `app/src/main/assets/deepbot/ds-bot.zip`（不入库） |

为什么用 commit 而不是分支：分支会移动，同一份 `versions.env` 第二天就打不出相同的包。
为什么不在仓库里存 zip：快照会过期，而且没人会在升级时想起它——"引用二开仓库"就成了一句空话。

## 构建顺序

```bash
./scripts/collect-termux-runtime.sh app/src/main/assets/runtime.zip aarch64   # ① 引擎运行时（含全部安卓补丁）
./scripts/fetch-dsbot.sh                                                     # ② 二开 ds-bot
./gradlew assembleDebug                                                      # ③ 壳
```

`collect-termux-runtime.sh` 是补丁清单的**唯一来源**（koffi/node-pty 桩、flock、
apiproxy、webview-polyfill、attachment-local、require-builtin 回退、rgPath 前缀改写、
collapsedWidth=0），本文不复制清单——复制的清单迟早与脚本不同步。

## 换版本时

1. 改 `versions.env`（引擎版本/integrity，或 ds-bot commit）；
2. 重跑上面三步；
3. 记录新产物：`sha256sum app/build/outputs/apk/debug/app-debug.apk` 与 `runtime.zip`；
4. 引擎升级后先过 `scripts/verify-runtime-abi.py`，再装机。

## 本机（Android / Termux 环境）的两个坑

- 存储是 FUSE，**不允许建符号链接**：从 npm 树复制 `node_modules` 必须 `cp -rL`。
- `/storage/emulated/0` 是 **noexec**：引擎自检要在 `$HOME`（内部存储）里跑，
  `bin/node` 才能执行。
