# runtime 安卓侧补丁（升级引擎时必须逐条复核）

`assets/runtime.zip` 不是上游原样产物：它由 `scripts/collect-termux-runtime.sh` 收 Termux
基础层 + `@deepseek-ai/dsh` 依赖闭包，再**打下面这些补丁**。升级引擎版本时，逐条判断
「还需要吗、位置变了吗」，不要直接沿用旧判断。

判断方法统一是：打补丁前跑一次脚本，看它输出 `patched` / `already patched` / `WARN`。
`WARN` 不一定是错，但必须解释清楚，并写回本文件。

---

## 1. `koffi` → 惰性桩 `0.0.0-android-inert`（包级）

- **为什么**：koffi 是 FFI 库，只有 glibc/x64 预编译。Android 上真实 FFI 调用不会发生，
  但它的**类型注册在模块顶层执行**，直接 import 会抛错。
- **原来长什么样**：`node_modules/koffi/` 整个包（含 vendor 预编译）。
- **补丁形态**：`package.json` 改为 `{"name":"koffi","version":"0.0.0-android-inert","main":"index.js"}`，
  `index.js` 是一个 Proxy：任何属性访问返回惰性函数、`construct` 返回 `{}`、`then` 返回
  `undefined`（避免被当 thenable）。原文见 `collect-termux-runtime.sh` 的 `[koffi]` 段。
- **升级时怎么判断**：`npm install --ignore-scripts` 出来的树里如果**没有** `koffi`
  （0.2.0+ 可能真的不依赖了），就不用打；有就必须打，且必须是惰性桩。

## 2. `node-pty` → 兼容壳 `0.0.0-android-shim`（包级）

- **为什么**：node-pty 的 prebuilds 只有 darwin/linux/win32，**没有 android**；
  App 层的 PTY 由自研 `libdshpty.so` 提供（roadmap M2 才桥接）。
- **原来长什么样**：真包 + 8 个 `.node` 预编译产物（约 25.6 MB，Android 全用不到）。
- **补丁形态**：`{"name":"node-pty","version":"0.0.0-android-shim","main":"lib/index.js"}`，
  `lib/index.js` 的 `spawn()` 直接抛「node-pty 在本 Android 构建不可用」。
- **升级时怎么判断**：新树里有没有 `node-pty`；有就必须换成壳。顺手看 `.node` 清单——
  除 node-pty 外如果出现别的 `.node`，说明 `--ignore-scripts` 漏掉了新的原生依赖，
  需要单独报告，不能默认跳过。

## 3. `@vscode/ripgrep-android-arm64` → 保留平台二进制（包级）

- **为什么**：`@deepseek-ai/dsh-tool-fs-search` 依赖 `@vscode/ripgrep`，而后者靠
  postinstall 下载平台二进制；我们用 `--ignore-scripts` 安装，**下载不会执行**，
  于是缺安卓 arm64 的 `rg`，文件搜索直接失效。
- **原来长什么样**：`node_modules/@vscode/ripgrep-android-arm64/`（约 4.7 MB，含 `bin/rg`）。
- **补丁形态**：整目录从上一个 rc 的 runtime 树里拷回来（不要重新下载，网络不可控）。
- **升级时怎么判断**：新树里 `ls node_modules/@vscode/` 只有 `ripgrep` 就说明又丢了；
  同时确认 `bin/rg` 是 arm64（`file bin/rg` 或读 ELF e_machine=0xB7）。

## 4. `@deepseek-ai/node-addon-system/flock` → Android no-op（代码级）

- **为什么**：上游 `loadBinding()` 只接受 linux/darwin，Android 上抛
  `ERR_FLOCK_UNSUPPORTED_PLATFORM`；而 `dsh-session-persistence-jsonl` 在**会话落盘**
  路径上调用 `tryLockExclusive`，非 contention 的错误会直接上抛 → **引擎启动即崩**
  （v1.2.28 事故根因）。
- **补丁形态**：`lib/flock.js` 整体覆写为 `export async function tryLockExclusive(fd){void fd;return;}`
- **不改是否可行**：不可行。这条不打，rc2 引擎会在写会话时崩。
- **升级时怎么判断**：`node_modules/@deepseek-ai/node-addon-system/lib/flock.js` 是否还存在、
  导出的函数名是否还是 `tryLockExclusive`。名字变了要按新名重写。

## 5. `patch-apiproxy.py`（代码级）—— **rc2 起可能已不需要**

- **为什么当年要打**：rc1 的 `@deepseek-ai/dsh-host-apiproxy` 在点文件引用时调原生
  opener（macOS open / xdg-open / Windows start），Android 全无 → 报
  「native path opener is unsupported on android」。补丁改成在进程内读文件。
- **rc2 现状**：该包已不存在，逻辑搬到 `@deepseek-ai/dsh-native-command/lib/types/path-opener.js`，
  且 `canOpenNativePath()` 对非 darwin/win32/linux 的平台**已经返回 false**，
  UI 会走「把路径显示成文本」的降级路径。所以 `patch-apiproxy.py` 在 rc2 上输出
  `WARN: apiproxy not found`，**这是预期**，不是失败。
- **待办**：真机上点一次文件引用，确认不再出现 `unsupported on android`。若仍出现，
  按 rc2 的新位置（`dsh-native-command`）重新适配，并把结论写回本节。

## 6. `patch-webview-polyfill.py` / `patch-attachment-local.py`（代码级）

- **webview-polyfill**：Android 11 旧 WebView 缺 `AbortSignal.any` 等新 API → 白屏。
  补丁往 `@deepseek-ai/dsh-web-frontend/dist/index.html` 注入 polyfill。
  升级时看输出是否 `polyfill v3 injected`。
- **attachment-local**：SELinux 禁 `link()`、`ensureDurableHome` 的祖先遍历会爬出沙箱
  → 首次存图 `ATTACHMENT_WRITE_FAILED`。补丁脚本自带 5 项校验，升级时看
  `校验: sync 容忍注入=OK | EACCES/EPERM 覆盖=OK | EINVAL/EROFS 覆盖=OK | link shim=OK | 无裸 rename as link=OK`，
  **任一项不是 OK 就不能打包**。

---

## 打补丁的顺序（不要调换）

```
1) 整目录替换 lib/node_modules（不合并旧树，避免版本混装）
2) 回填 3 个包级补丁（koffi / node-pty / ripgrep-android-arm64）
3) 修剪 *.md / *.map / *.d.ts（LICENSE 一律保留）
4) flock 覆写（4）
5) patch-apiproxy.py（可能 WARN，见 5）→ patch-webview-polyfill.py → patch-attachment-local.py
6) 真起引擎自检：端口探通 + 文件搜索能用（rg 链路）
7) dedupe-runtime.py → rezip-runtime.py → patch-curl-wrapper.py → verify-runtime-abi.py
8) MANIFEST.version / sha256 更新（version 变了才会触发 .stamp 重装）
```

## 已知环境限制

- 本机存储（FUSE）**不允许创建符号链接**：从 npm 树拷 `node_modules` 时必须用
  `cp -rL`（解引用），否则 `.bin/*` 全部 `Permission denied`。旧 runtime.zip 里
  0 个软链也是这个原因（`dedupe-runtime.py` 顶部有同样说明）。
