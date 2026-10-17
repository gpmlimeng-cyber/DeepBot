/**
 * [dsh-android] 窄屏侧栏全宽插件（v1.2.175）—— Cordis 客户端插件。
 *
 * ## 这个包解决什么
 * 真机竖屏（1216x2640，约 405dp 宽）下，引擎自带的布局在视口 < 1024px 时进入
 * `narrow` 模式：用户手动展开侧栏后，`computeColumns()` 仍按桌面规则分配列宽
 * （侧栏 264~420px + 对话区至少留 400px 的假设），对话区被压成一条窄缝
 * （实测 336px），文字逐字换行、完全不可读——用户截图取证。
 *
 * ## 为什么做成插件（而不是改引擎源码 / 外部注入 JS）
 * 用户明确要求：**不要改源码，做成插件**，否则与其他 UI 插件冲突。
 * 引擎的客户端模块系统（`dsh-client-modules`）支持标准 npm 包形式的 UI 插件：
 * 包在 package.json 声明 `dsh.client` 并提供 `exports["./client"]`，引擎扫描
 * Loader entries 时自动发现并把浏览器端 bundle 注入页面。所以本插件是一个
 * **标准包**（package.json + index.js + client.js），随 android-overlay.yml
 * 的 entry 一起被加载，完全不碰引擎文件。
 *
 * ## 为什么不复用引擎的 narrowExpanded
 * 引擎的窄屏逻辑是「手动展开 = 覆盖在挤压的中心区之上」，那是**桌面**语义
 * （中心区本来就够宽）。手机上中心区被压到 336px 无可读性，且用户要的是
 * 「侧栏全展开 + 隐藏对话流」。因此本插件采用**覆盖式**布局：不动引擎的
 * 列宽计算（不改 React 状态、不抢 DOM），只在自己的 client 端注入一段样式，
 * 在「窄屏 + 侧栏展开」时让侧栏列横跨全宽、中心列让位。
 *
 * ## 冲突防护（用户关切）
 * · 只加一个 `data-android-narrow` 根属性 + 一段带自身命名空间的 `<style>`，
 *   选择器全部以 `[data-android-narrow]` 为前缀，不覆盖引擎原有规则；
 * · 引擎/其他插件若重新渲染，本插件的 ResizeObserver + MutationObserver 会
 *   重新应用；我们的样式表在 `<head>` 末尾插入，不修改任何已有节点；
 * · 卸载时（dispose）移除属性、样式表与观察器，零残留。
 *
 * ## 环境判定（用户要求「做个屏幕判定，看看会不会导致屏幕显示异常」）
 * 仅在**竖屏手机**生效，以下任一条件不满足就完全不动：
 *   ① 视口宽度 <= MAX_VIEWPORT_PX（900）—— 横屏/桌面模式通常 > 900，排除；
 *   ② 视口高度 > 宽度 —— 必须是竖屏；
 *   ③ 侧栏处于展开态 —— 折叠时保持引擎原样（图标栏）。
 * 横屏（平板/桌面模式/手机横屏）一律不启用 → 不会影响宽屏显示。
 *
 * @module dsh-android-narrow-sidebar
 */

/** Cordis 插件名（宿主端；本包宿主侧不做事，只是为了出现在 Loader 里）。 */
const name = "android-narrow-sidebar";

/** 本插件宿主侧不依赖任何服务。 */
const inject = [];

/**
 * 宿主端插件体：空实现。
 *
 * 与 `dsh-cordis-client-runner` / `dsh-client-ui-cordis` 的约定一致 ——
 * 纯 UI 插件需要一条 Loader entry 才会被 client-modules 扫描到，而这条 entry
 * 必须能被宿主 import 成功，所以导出一个空 `apply`。
 */
function apply() {}

export { apply, inject, name };
