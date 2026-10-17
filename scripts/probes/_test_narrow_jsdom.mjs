/**
 * [dsh-android] narrow-sidebar 插件 —— 真实引擎结构验证（v1.2.176）。
 *
 * 与 `_test_narrow_sidebar.mjs` 的区别：那个用自写 DOM 桩；这个用 **jsdom**
 * （真实 DOM 实现：getComputedStyle / nextElementSibling / 事件 / 观察器全是真的），
 * 且 DOM 结构**直接照抄引擎 `dsh-client-ui-layout` 的 AppFrame 渲染结果**：
 *
 *   <div class="pI_x6G_frame" style="display:grid; grid-template-columns:...">
 *     <div class="pI_x6G_sidebarCol">   ← 含"收起侧边栏"按钮
 *     <div class="pI_x6G_centerCol">    ← 对话区
 *     <div class="pI_x6G_overlayLayer">
 *     (可选) <div class="pI_x6G_rightbarCol">
 *   </div>
 *
 * 这个结构来自真机取证（拉取 `@deepseek-ai/dsh-client-ui-layout/lib/client.js`
 * 的 AppFrame 实现 + CSS），不是臆测。
 *
 * 运行：node _test_narrow_jsdom.mjs
 */

import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

const PLUGIN = new URL(
  "../../app/src/main/assets/android-plugins/narrow-sidebar/client.js",
  import.meta.url,
);
const PKG_NAME = "dsh-android-narrow-sidebar";
const SB_ATTR = "data-android-narrow-sidebar-col";
const YIELD_ATTR = "data-android-narrow-yield-col";
const STYLE_ID = "dsh-android-narrow-sidebar-style";

let pass = 0, fail = 0;
const check = (label, cond) => {
  if (cond) { console.log("   ✓ " + label); pass++; }
  else { console.log("   ✗ " + label); fail++; }
};

/**
 * 复刻引擎 AppFrame 的真实 DOM 与内联网格样式。
 * @param {{width:number, height:number, expanded:boolean, rightbar?:boolean}} opts
 */
function buildFrame({ width, height, expanded, rightbar = false }) {
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>
      <div id="frame" style="display:grid;grid-template-columns:280px minmax(400px,1fr) minmax(0px,0px);height:100vh">
        <div id="sidebar" class="pI_x6G_sidebarCol" style="position:relative">
          ${expanded ? '<button aria-label="收起侧边栏">toggle</button>' : '<button>rail</button>'}
        </div>
        <div id="center" class="pI_x6G_centerCol" style="position:relative">对话内容</div>
        <div id="overlay" class="pI_x6G_overlayLayer" data-shell-overlay style="position:absolute;inset:0;pointer-events:none"></div>
        ${rightbar ? '<div id="rightbar" class="pI_x6G_rightbarCol" style="position:relative">轨迹</div>' : ""}
      </div>
    </body></html>`,
    { pretendToBeVisual: true, runScripts: "outside-only", url: "http://127.0.0.1:3080/" },
  );
  // jsdom 不跑布局，getBoundingClientRect 恒为 0；但插件只读 innerWidth/innerHeight
  // 与 display 计算值，不依赖真实盒模型尺寸。
  Object.defineProperty(dom.window, "innerWidth", { value: width, writable: true, configurable: true });
  Object.defineProperty(dom.window, "innerHeight", { value: height, writable: true, configurable: true });
  return dom;
}

/** 在给定 jsdom 窗口里加载并激活插件，返回 dispose。 */
function activate(dom) {
  const win = dom.window;
  let reg = null;
  win.__ModuleLoader__ = { load: (r) => { reg = r; } };
  // 插件 bundle 在浏览器里是**经典 script**（非 module），直接用 vm 上下文执行：
  // jsdom 的 window.eval 在该上下文里求值，window/document 都可见。
  const code = readFileSync(PLUGIN, "utf8");
  const vm = win.eval.bind(win);
  vm(code);
  if (!reg) throw new Error("bundle 未注册");
  if (reg.id !== PKG_NAME) throw new Error("注册 id 不匹配：" + reg.id);
  const mod = reg.factory((name) => { throw new Error("不应 require：" + name); });
  // 插件内部用 rAF 合并判定；换成同步执行让测试确定。
  win.requestAnimationFrame = (fn) => { fn(); return 1; };
  win.cancelAnimationFrame = () => {};
  return { dispose: mod.apply({}), reg };
}

function grid(dom) { return dom.window.document.getElementById("frame"); }
function sidebar(dom) { return dom.window.document.getElementById("sidebar"); }
function center(dom) { return dom.window.document.getElementById("center"); }
function styleEl(dom) { return dom.window.document.getElementById(STYLE_ID); }

/* ── ① 用户场景：竖屏 + 侧栏展开 → 接管 ─────────────────────── */
console.log("\n=== ① 竖屏 405x880 + 侧栏展开（用户报告的场景）===");
{
  const dom = buildFrame({ width: 405, height: 880, expanded: true });
  const { dispose } = activate(dom);
  check("侧栏列被标记", sidebar(dom).hasAttribute(SB_ATTR));
  check("中心列让位", center(dom).hasAttribute(YIELD_ATTR));
  const css = styleEl(dom)?.textContent ?? "";
  check("样式表已注入", !!styleEl(dom));
  check("侧栏 CSS 为跨全宽", css.includes(`[${SB_ATTR}] { grid-column: 1 / -1; }`));
  check("让位 CSS 为 display:none", css.includes(`[${YIELD_ATTR}] { display: none; }`));
  // 关键：JS 计算的样式确实生效（jsdom 支持 inline style 计算）
  check("侧栏 calc 后 display 未被改（仍可见）",
        dom.window.getComputedStyle(sidebar(dom)).display !== "none");
  dispose();
  check("dispose 后标记清除", !sidebar(dom).hasAttribute(SB_ATTR));
  check("dispose 后样式表移除", !styleEl(dom));
}

/* ── ② 竖屏折叠 / ③ 横屏 / ④ 宽屏 → 不接管 ───────────────── */
for (const [label, opts] of [
  ["② 竖屏 405x880 + 侧栏折叠", { width: 405, height: 880, expanded: false }],
  ["③ 手机横屏 880x405", { width: 880, height: 405, expanded: true }],
  ["④ 桌面模式 1280x800", { width: 1280, height: 800, expanded: true }],
  ["⑤ 平板竖屏 800x1200（宽 ≤900 且竖屏）→ 应接管", { width: 800, height: 1200, expanded: true }],
]) {
  console.log("\n=== " + label + " ===");
  const dom = buildFrame(opts);
  const { dispose } = activate(dom);
  const want = label.startsWith("⑤");
  check("侧栏列 " + (want ? "已接管" : "未接管"), sidebar(dom).hasAttribute(SB_ATTR) === want);
  check("样式表 " + (want ? "已注入" : "未注入"), !!styleEl(dom) === want);
  dispose();
}

/* ── ⑥ 转屏：竖 → 横 → 竖 ──────────────────────────────────── */
console.log("\n=== ⑥ 转屏：竖屏接管 → 转横屏恢复 → 转回竖屏再接管 ===");
{
  const dom = buildFrame({ width: 405, height: 880, expanded: true });
  const { dispose } = activate(dom);
  check("初始接管", sidebar(dom).hasAttribute(SB_ATTR));
  const win = dom.window;
  win.innerWidth = 880; win.innerHeight = 405;
  win.dispatchEvent(new win.Event("resize"));
  check("转横屏后恢复", !sidebar(dom).hasAttribute(SB_ATTR));
  check("转横屏后样式表移除", !styleEl(dom));
  win.innerWidth = 405; win.innerHeight = 880;
  win.dispatchEvent(new win.Event("resize"));
  check("转回竖屏再接管", sidebar(dom).hasAttribute(SB_ATTR));
  dispose();
}

/* ── ⑦ 侧栏晚于插件出现（真实加载顺序）→ 后续判定补上 ──────── */
console.log("\n=== ⑦ 侧栏晚出现（插件先激活，随后引擎渲染侧栏）===");
{
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>
      <div id="frame" style="display:grid;grid-template-columns:280px 1fr;height:100vh">
        <div id="sidebar" class="pI_x6G_sidebarCol"></div>
        <div id="center" class="pI_x6G_centerCol"></div>
      </div></body></html>`,
    { pretendToBeVisual: true, runScripts: "outside-only", url: "http://127.0.0.1:3080/" },
  );
  const win = dom.window;
  Object.defineProperty(win, "innerWidth", { value: 405, writable: true, configurable: true });
  Object.defineProperty(win, "innerHeight", { value: 880, writable: true, configurable: true });
  const { dispose } = activate(dom);
  check("初始无折叠按钮 → 不接管", !sidebar(dom).hasAttribute(SB_ATTR));
  // 引擎随后渲染出按钮（模拟 React 挂载）
  const btn = win.document.createElement("button");
  btn.setAttribute("aria-label", "收起侧边栏");
  sidebar(dom).appendChild(btn);
  // 触发观察器/定时器：jsdom 的 MutationObserver 是异步微任务
  await new Promise((r) => win.setTimeout(r, 60));
  check("侧栏出现后自动接管", sidebar(dom).hasAttribute(SB_ATTR));
  dispose();
}

/* ── ⑧ 有右栏时：右栏也让位 ───────────────────────────────── */
console.log("\n=== ⑧ 网格含右栏（轨迹面板）→ 右栏也让位 ===");
{
  const dom = buildFrame({ width: 405, height: 880, expanded: true, rightbar: true });
  const { dispose } = activate(dom);
  check("侧栏接管", sidebar(dom).hasAttribute(SB_ATTR));
  check("中心列让位", center(dom).hasAttribute(YIELD_ATTR));
  check("右栏也让位", dom.window.document.getElementById("rightbar").hasAttribute(YIELD_ATTR));
  check("overlay 未被误伤",
        !dom.window.document.getElementById("overlay").hasAttribute(YIELD_ATTR) &&
        !dom.window.document.getElementById("overlay").hasAttribute(SB_ATTR));
  dispose();
}

console.log("\n────────────────────────────");
console.log("通过 " + pass + " / 失败 " + fail);
process.exit(fail === 0 ? 0 : 1);
