/**
 * [dsh-android] 窄屏右侧面板全屏化 —— 浏览器端 bundle（v1.2.181）。
 *
 * ## 对应问题（GitHub Issue #16「更改无法查看」）
 * 用户实测：手机竖屏点开「查看更改」后，右侧面板在**页面缩放 60% 及以上完全不显示**，
 * 只有调到 50% 左右才能在右侧看到。
 *
 * ## 根因（引擎源码取证 + 数值验算，非推测）
 * 1. 面板列宽由 `dsh-client-ui-layout` 的 `computeColumns()` 决定：
 *    ```
 *    const s = sidebar === 0 ? collapsedWidth : clamp(sidebar, 264, 420);
 *    const available = viewport - s - 400;          // 固定扣掉"中心区最少 400px"
 *    const r = rightbar === 0 || available < 300 ? 0 : min(available, clamp(...));
 *    ```
 *    → 视口 386px 时 `available = 386 - 56 - 400 = -70 < 300` → **r = 0**，
 *      右栏宽度直接为 0（不是被挤压，是**根本没有列**）。
 * 2. 引擎自身的窄屏适配在 `dsh-client-ui-sidebar-right`：
 *    `const autoFullscreen = viewportWidth < 768;` → 窄屏应走全屏呈现
 *    （`width:100vw`），但因为 ① 的列宽为 0，全屏面板挂在 **0 宽列**里，
 *    其动画变量 `--dsh-sidebar-width` 被内联设为 `100vw`，
 *    dock 的 `transform: translateX(var(--dsh-sidebar-width))` 把内容整体
 *    推到屏幕右外侧 → 用户看不到（但面板 DOM 是存在的）。
 * 3. 另外 `canShow = normal.rightbar > 0` 为 false 时，面板会
 *    `actions.setExpanded(false)` 自行收起。
 *
 * ## 本插件的修法（最小侵入）
 * **只在面板确实处于打开态**（引擎自标的 `data-sidebar-right-open` 属性存在）时：
 *   · 把面板从 0 宽列里"提出来"：`position: fixed; inset: 0; width: 100vw`
 *     —— 脱离文档流，不再受父列宽度约束；
 *   · 把 `--dsh-sidebar-width` 归零、对 dock/empty/divider 强制
 *     `visibility: visible; transform: none` —— 抵消那个把内容推出屏外的位移。
 *
 * 面板**关闭时一律不动**（连属性都不加），因此不可能出现"凭空冒出面板"。
 * 宽屏（> 900px）完全不动，保留引擎自己的分栏布局。
 *
 * ## 元素锚点（引擎源码取证）
 * `dsh-client-ui-sidebar-right` 的 `SidebarPanel` 渲染：
 * ```html
 * <div data-sidebar-right-panel="fullscreen|push"
 *      data-sidebar-right-open        ← 打开时才存在
 *      aria-hidden="false|true"
 *      style="width:100vw; --dsh-sidebar-width:100vw">
 *   <div class="panelBody">
 *     <div data-dockkit-host="dock"> …  ← 被 translateX 推出屏外的就是它
 * ```
 * 这些都是引擎的**语义属性**（不是哈希类名），版本变动风险低。
 *
 * ## 冲突防护 / 可回滚
 * 样式表插在 `<head>` 末尾，选择器全部以本插件属性 `data-android-narrow-rightbar`
 * 为前缀；卸载或面板关闭时移除标记与样式表，引擎恢复原样。
 *
 * ## 注册约定（同其它 android 客户端插件，实测确认）
 * 客户端 bundle 由引擎以**经典 script** 加载，必须调用
 * `window.__ModuleLoader__.load({ id, factory })`，且 `id` 严格等于包名。
 *
 * @module dsh-android-narrow-rightbar/client
 */

window.__ModuleLoader__.load({
  id: "dsh-android-narrow-rightbar",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    /** 视口宽度上限：超过视为宽屏（平板横屏/桌面），保留引擎分栏。 */
    const MAX_VIEWPORT_PX = 900;

    /** 面板标记属性（打在引擎的右侧面板根元素上）。 */
    const PANEL_ATTR = "data-android-narrow-rightbar";

    /** 样式表 id（幂等）。 */
    const STYLE_ID = "dsh-android-narrow-rightbar-style";

    /**
     * 注入的样式：把已打开的右栏面板提升为整屏浮层。
     *
     * 关键点：
     *  · `position: fixed` 让面板脱离 0 宽的网格列；
     *  · `--dsh-sidebar-width: 0px` + `transform: none` 抵消引擎的滑出动画位移
     *    （引擎在关闭态把 dock 平移 `--dsh-sidebar-width` 并 visibility:hidden，
     *    全屏模式下该变量是 100vw → 内容被推到屏幕外）；
     *  · `visibility: visible` 兜底：即使引擎把 dock 标成 hidden，也让它显示
     *    （只在面板处于打开态时才会打标记，遵循用户意图）。
     */
    const STYLE_TEXT = [
      "/* 面板：整屏浮层，脱离 0 宽的网格列 */",
      "[" + PANEL_ATTR + "] {",
      "  position: fixed !important;",
      "  inset: 0 !important;",
      "  width: 100vw !important;",
      "  max-width: 100vw !important;",
      "  height: 100vh !important;",
      "  z-index: 60 !important;",
      "  pointer-events: auto !important;",
      "  --dsh-sidebar-width: 0px !important;",
      "}",
      "/* dock 内容：抵消 translateX(100vw) 的滑出位移，强制可见 */",
      "[" + PANEL_ATTR + "] [data-dockkit-host=\"dock\"],",
      "[" + PANEL_ATTR + "] [data-dockkit-empty],",
      "[" + PANEL_ATTR + "] [data-dockkit-divider] {",
      "  visibility: visible !important;",
      "  transform: none !important;",
      "  transition: none !important;",
      "}",
    ].join("\n");

    /** 移除样式表（幂等）。 */
    function removeStyle() {
      var old = document.getElementById(STYLE_ID);
      var p = old && (old.parentElement || old.parentNode);
      if (p) p.removeChild(old);
    }

    /** 插入样式表。 */
    function insertStyle() {
      if (document.getElementById(STYLE_ID)) return;
      var style = document.createElement("style");
      style.id = STYLE_ID;
      style.textContent = STYLE_TEXT;
      (document.head || document.documentElement).appendChild(style);
    }

    /**
     * 找当前打开的右侧面板。
     *
     * 判据（全部来自引擎语义属性）：
     *   ① 元素自身带 `data-sidebar-right-panel`（值 fullscreen/push）；
     *   ② 带 `data-sidebar-right-open`（只在打开时出现）—— 缺一不可。
     * 关闭态返回 null，本插件完全不动。
     *
     * @returns {Element | null}
     */
    function findOpenPanel() {
      var list = document.querySelectorAll("[data-sidebar-right-panel]");
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        if (el.hasAttribute("data-sidebar-right-open")) return el;
      }
      return null;
    }

    /**
     * 浏览器端插件体。
     * @param {object} ctx - 客户端 Cordis 上下文。
     * @returns {() => void} 卸载函数。
     */
    function apply(ctx) {
      /** 是否处于接管态。 */
      var marked = false;
      /** 被标记的面板（持引用，撤销是纯内存操作）。 */
      var markedPanel = null;
      /** rAF 是否已排队（在调用前置位，避免同步 rAF 下的竞态）。 */
      var pending = false;
      var cleanups = [];

      /** 撤销：移除标记与样式表。 */
      function unmark() {
        if (markedPanel) {
          markedPanel.removeAttribute(PANEL_ATTR);
          markedPanel = null;
        }
        removeStyle();
        marked = false;
      }

      /** 应用：打标记 + 注入样式。 */
      function mark(panel) {
        insertStyle();
        panel.setAttribute(PANEL_ATTR, "");
        markedPanel = panel;
        marked = true;
        // [dsh-android] 前缀 → MainActivity.onConsoleMessage 会转发到 engine.log
        console.log(
          "[dsh-android] narrow-rightbar: 已接管右侧面板（视口 " +
            window.innerWidth + "x" + window.innerHeight + "）"
        );
      }

      /** 重新判定。 */
      function refresh() {
        pending = false;
        var narrow = window.innerWidth <= MAX_VIEWPORT_PX;
        var panel = narrow ? findOpenPanel() : null;
        if (!panel) {
          if (marked) {
            unmark();
            console.log("[dsh-android] narrow-rightbar: 已恢复（面板关闭或宽屏）");
          }
          return;
        }
        if (!marked) {
          mark(panel);
        } else if (markedPanel !== panel) {
          // 引擎重建了面板（切会话/换 tab）→ 标记迁移
          unmark();
          mark(panel);
        }
      }

      /**
       * 合并高频触发。
       * ⚠️ pending 必须在调用 rAF **之前**置位：若 rAF 同步执行（测试桩），
       * refresh 内清掉的标志会被返回值写回，之后所有 schedule 被挡掉。
       */
      function schedule() {
        if (pending) return;
        pending = true;
        window.requestAnimationFrame(refresh);
      }

      schedule();
      var timers = [300, 1200, 2600].map(function (ms) {
        return window.setTimeout(schedule, ms);
      });
      cleanups.push(function () {
        timers.forEach(function (t) { window.clearTimeout(t); });
      });

      window.addEventListener("resize", schedule);
      cleanups.push(function () { window.removeEventListener("resize", schedule); });
      window.addEventListener("orientationchange", schedule);
      cleanups.push(function () {
        window.removeEventListener("orientationchange", schedule);
      });

      // 属性变化（打开/关闭面板）也会触发 MutationObserver
      try {
        var mo = new MutationObserver(schedule);
        mo.observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
        });
        cleanups.push(function () { mo.disconnect(); });
      } catch (e) {
        /* 观察器不可用时退化为首帧判定 */
      }

      return function () {
        for (var i = 0; i < cleanups.length; i++) {
          try { cleanups[i](); } catch (e) { /* 卸载阶段忽略 */ }
        }
        unmark();
      };
    }

    exports.apply = apply;
    return module.exports;
  },
});
