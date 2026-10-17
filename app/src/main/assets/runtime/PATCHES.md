# runtime 安卓侧补丁：**唯一来源是 `scripts/collect-termux-runtime.sh`**

`assets/runtime.zip` 不是上游原样产物。它由 `scripts/collect-termux-runtime.sh`
收 Termux 基础层 + `@deepseek-ai/dsh` 依赖闭包，再打一系列 bionic 兼容补丁。

**升级引擎一律走这个脚本，不许手工替换 `node_modules`。**
本文不再抄写补丁清单（抄了必然与脚本不同步）；要知道打了什么、为什么打，
直接读脚本里的注释——每一段都写明了「为什么需要 / 不打会怎样 / 幂等做法」。

## 升级到新版本时，只需要做两件判断

1. **脚本跑完有没有 WARN**。`WARN` 不一定是错（例如包被上游移除、补丁目标不存在），
   但每一条都必须解释清楚，并把结论补进脚本该段的注释。
2. **两道硬门槛必须过**，缺一不可：
   - 用新树在临时 `HOME` 里**真起一次引擎**：端口能探通（`401` 即可）、进程存活；
   - 文件搜索链路能用：`@vscode/ripgrep` 解析出的 `rg` 可执行（arm64），
     `dsh-tool-fs-search` 能加载。

## 本机（Android / Termux 环境）跑这套脚本的两个已知限制

- 存储是 FUSE，**不允许创建符号链接**：从 npm 树拷 `node_modules` 必须用
  `cp -rL`（解引用），否则 `.bin/*` 全部 `Permission denied`。
- `/storage/emulated/0` 是 **noexec**：自检要在 `$HOME`（内部存储）里跑，
  `bin/node` 才能执行；打好的 zip 再放回 `assets/`。
- `npm install` 那步脚本给了 `--max-old-space-size=5632`；本机内存不够时不要硬跑，
  直接复用已经装好的树，只做「替换 + 打补丁 + 打包」。
