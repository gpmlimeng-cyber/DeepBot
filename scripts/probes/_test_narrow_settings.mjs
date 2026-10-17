/**
 * [dsh-android] narrow-settings 插件验证（v1.2.178）。
 *
 * 用 jsdom（真实 DOM）复刻引擎设置面板的结构与样式 —— 数值全部来自
 * 真机取证（uiautomator dump + 拉取 `dsh-client-ui-settings-general` 的 CSS）：
 *
 *   面板 .VOzbGW_panel   width:800px; max-width:calc(100vw - 48px)
 *   导航 .VOzbGW_nav     width:188px; flex:none
 *   内容 .VOzbGW_content flex:1; min-width:0
 *
 * 验证场景：
 *   ① 窄屏（386px）+ 面板打开 → 接管（面板/导航/内容三项标记 + 样式表）
 *   ② 宽屏（1280px）→ 不接管（引擎原样）
 *   ③ 面板未打开 → 不接管
 *   ④ 面板关闭后（DOM 移除）→ 自动撤销
 *   ⑤ 转屏：窄 → 宽 → 窄
 *   ⑥ dispose 零残留
 *   ⑦ 回归：其它固定定位浮层（对话框，按钮少）不被误伤
 *
 * 运行：node _test_narrow_settings.mjs
 */

import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

const PLUGIN = new URL(
  "../../app/src/main/assets/android-plugins/narrow-settings/client.js",
  import.meta.url,
);
const PKG_NAME = "dsh-android-narrow-settings";
const PANEL_ATTR = "data-android-narrow-settings";
const NAV_ATTR = "data-android-narrow-settings-nav";
const CONTENT_ATTR = "data-android-narrow-settings-content";
const STYLE_ID = "dsh-android-narrow-settings-style";

let pass = 0, fail = 0;
const check = (label, cond) => {
  if (cond) { console.log("   ✓ " + label); pass++; }
  else { console.log("   ✗ " + label); fail++; }
};

/**
 * 复刻引擎设置面板 DOM。
 *
 * @param {{width:number, height:number, open:boolean}} opts - 视口与是否打开面板。
 */
function buildSettings({ width, height, open }) {
  // 主界面（面板的背景），保证面板是"浮层"
  const body = open
    ? `<div id="app">主界面</div>
       <div id="mask" style="position:fixed;inset:0"></div>
       <div id="overlay" class="VOzbGW_overlay" style="position:fixed;inset:0;display:flex;justify-content:center;align-items:center">
         <div id="mask" class="VOzbGW_mask" aria-hidden="true" style="position:fixed;inset:0"></div>
         <div id="panel" class="VOzbGW_panel" role="dialog" aria-modal="true" style="position:relative;width:800px;max-width:calc(100vw - 48px);display:flex">
           <nav id="nav" class="VOzbGW_nav" style="width:188px;flex:none;display:flex;flex-direction:column">
             <div id="navTitle" class="VOzbGW_navTitle">设置</div>
             <div id="navList" class="VOzbGW_navList" style="display:flex;flex-direction:column">
               <button class="VOzbGW_navCell"><span class="VOzbGW_navLabel">通用设置</span></button>
               <button class="VOzbGW_navCell"><span class="VOzbGW_navLabel">模型</span></button>
               <button class="VOzbGW_navCell"><span class="VOzbGW_navLabel">内置插件</span></button>
               <button class="VOzbGW_navCell"><span class="VOzbGW_navLabel">Agent 预设</span></button>
             </div>
           </nav>
           <div id="content" class="VOzbGW_content" style="flex:1;min-width:0;display:flex;flex-direction:column">
             <div id="header" class="VOzbGW_header">设置 <button aria-label="关闭">×</button></div>
             <div id="options" class="VOzbGW_options">
               <div id="row1"><span id="label1">选择新会话的默认权限模式</span><span>完全权限</span></div>
               <div id="row2"><span>语言</span><span>中文</span></div>
             </div>
           </div>
         </div>
       </div>`
    : `<div id="app">主界面</div>`;

  // getBoundingClientRect 在 jsdom 里恒为 0 —— 插件的 findPanel 用宽高比例筛选，
  // 因此这里给关键元素打上真实尺寸（来自真机取证），让判定逻辑可被验证。
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>${body}</body></html>`,
    { pretendToBeVisual: true, runScripts: "outside-only", url: "http://127.0.0.1:3080/" },
  );
  const win = dom.window;
  Object.defineProperty(win, "innerWidth", { value: width, writable: true, configurable: true });
  Object.defineProperty(win, "innerHeight", { value: height, writable: true, configurable: true });

  // 给面板与两列注入与真实布局一致的尺寸（真机 386 视口下的实测近似值）
  const panel = win.document.getElementById("panel");
  const nav = win.document.getElementById("nav");
  const content = win.document.getElementById("content");
  const rects = {
    panel: { width: Math.min(800, width - 48), height: Math.min(800, height - 48) },
    nav: { width: 188, height: Math.min(800, height - 48) },
    content: { width: Math.max(0, Math.min(800, width - 48) - 188), height: Math.min(800, height - 48) },
  };
  if (panel) panel.getBoundingClientRect = () => ({ ...rects.panel, top: 24, left: 24, right: 24 + rects.panel.width, bottom: 24 + rects.panel.height });
  if (nav) nav.getBoundingClientRect = () => ({ ...rects.nav, top: 24, left: 24, right: 24 + rects.nav.width, bottom: 24 + rects.nav.height });
  if (content) content.getBoundingClientRect = () => ({ ...rects.content, top: 24, left: 212, right: 212 + rects.content.width, bottom: 24 + rects.content.height });

  return dom;
}

/** 载入并激活插件。 */
function activate(dom) {
  const win = dom.window;
  let reg = null;
  win.__ModuleLoader__ = { load: (r) => { reg = r; } };
  win.eval(readFileSync(PLUGIN, "utf8"));
  if (!reg) throw new Error("bundle 未注册");
  if (reg.id !== PKG_NAME) throw new Error("注册 id 不匹配：" + reg.id);
  const mod = reg.factory((n) => { throw new Error("不应 require：" + n); });
  win.requestAnimationFrame = (fn) => { fn(); return 1; };
  win.cancelAnimationFrame = () => {};
  return { dispose: mod.apply({}), reg };
}

const q = (dom, id) => dom.window.document.getElementById(id);
const styleEl = (dom) => dom.window.document.getElementById(STYLE_ID);

/* ① 用户场景：窄屏 + 面板打开 → 接管 */
console.log("\n=== ① 窄屏 386x777 + 设置面板打开（用户报告的场景）===");
{
  const dom = buildSettings({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  check("面板已标记", q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("导航列已标记", q(dom, "nav").hasAttribute(NAV_ATTR));
  check("内容列已标记", q(dom, "content").hasAttribute(CONTENT_ATTR));
  check("样式表已注入", !!styleEl(dom));
  const css = styleEl(dom)?.textContent ?? "";
  check("面板改为整宽", css.includes(`[${PANEL_ATTR}] {`) && /width:\s*calc\(100vw/.test(css));
  check("面板改为竖向堆叠", /flex-direction:\s*column\s*!important/.test(css));
  check("导航改横向", /flex-direction:\s*row\s*!important/.test(css));
  check("内容列拿满整宽", css.includes(`[${CONTENT_ATTR}] {`));
  // 断言方式：所有**选择器行**都必须引用本插件的标记属性。CSS 里形如
  // "[attr] > :first-child {" 的续行、以及单独的 "}" 不是选择器，跳过。
  const selectorLines = css
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith("/*") && l !== "}" && !l.startsWith("}") &&
                   !/^[a-z-]+:/.test(l)); // 跳过 "flex: ... !important;" 这类声明行
  check("所有选择器都引用本插件标记属性",
        selectorLines.every((l) => l.includes("data-android-narrow-settings")));
  check("CSS 里没有裸的引擎类名（无哈希类名依赖）",
        !/\.VOzbGW_|\.pI_x6G_|\.hHd-Xa_/.test(css));
  dispose();
  check("dispose 后面板标记清除", !q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("dispose 后样式表移除", !styleEl(dom));
}

/* ② 宽屏 → 不接管 */
console.log("\n=== ② 宽屏 1280x800 + 面板打开 → 不接管（引擎原样）===");
{
  const dom = buildSettings({ width: 1280, height: 800, open: true });
  const { dispose } = activate(dom);
  check("面板未标记", !q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("样式表未注入", !styleEl(dom));
  dispose();
}

/* ③ 面板未打开 → 不接管 */
console.log("\n=== ③ 窄屏但设置面板未打开 → 不接管 ===");
{
  const dom = buildSettings({ width: 386, height: 777, open: false });
  const { dispose } = activate(dom);
  check("无样式表注入", !styleEl(dom));
  dispose();
}

/* ④ 面板关闭 → 自动撤销 */
console.log("\n=== ④ 接管后面板关闭（DOM 移除）→ 自动撤销 ===");
{
  const dom = buildSettings({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  check("初始已接管", q(dom, "panel").hasAttribute(PANEL_ATTR));
  const overlay = q(dom, "overlay");
  overlay.parentNode.removeChild(overlay);
  // 触发观察器（MutationObserver 是异步微任务）
  await new Promise((r) => dom.window.setTimeout(r, 60));
  check("面板移除后样式表已清理", !styleEl(dom));
  dispose();
}

/* ⑤ 转屏：窄 → 宽 → 窄 */
console.log("\n=== ⑤ 转屏：窄屏接管 → 宽屏恢复 → 再窄屏接管 ===");
{
  const dom = buildSettings({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  check("初始接管", q(dom, "panel").hasAttribute(PANEL_ATTR));
  const win = dom.window;
  win.innerWidth = 1280; win.innerHeight = 800;
  win.dispatchEvent(new win.Event("resize"));
  check("转宽屏后恢复", !q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("转宽屏后样式表移除", !styleEl(dom));
  win.innerWidth = 386; win.innerHeight = 777;
  win.dispatchEvent(new win.Event("resize"));
  check("转回窄屏再接管", q(dom, "panel").hasAttribute(PANEL_ATTR));
  dispose();
}

/* ⑥ 回归：无关的固定定位浮层不被误伤 */
console.log("\n=== ⑥ 回归：普通对话框（按钮少）不被误伤 ===");
{
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>
      <div id="dlg" style="position:fixed;inset:0;display:flex">
        <div id="dlgBox" style="width:320px">确认删除？<button>取消</button><button>确定</button></div>
      </div></body></html>`,
    { pretendToBeVisual: true, runScripts: "outside-only", url: "http://127.0.0.1:3080/" },
  );
  const win = dom.window;
  Object.defineProperty(win, "innerWidth", { value: 386, writable: true, configurable: true });
  Object.defineProperty(win, "innerHeight", { value: 777, writable: true, configurable: true });
  const box = win.document.getElementById("dlgBox");
  box.getBoundingClientRect = () => ({ width: 320, height: 200, top: 200, left: 33, right: 353, bottom: 400 });
  const { dispose } = activate(dom);
  check("对话框未被标记", !box.hasAttribute(PANEL_ATTR));
  check("无样式表注入", !styleEl(dom));
  dispose();
}

console.log("\n────────────────────────────");

/* ⑪ 关键回归：必须标记在 panel 上，而不是外层 overlay（v1.2.179 真机事故） */
console.log("\n=== ⑪ 关键回归：标记必须落在 panel，不是 overlay ===");
{
  const dom = buildSettings({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  check("标记在 panel 上", q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("overlay 未被标记", !q(dom, "overlay").hasAttribute(PANEL_ATTR));
  check("nav 语义标签被标记为导航列", q(dom, "nav").hasAttribute(NAV_ATTR));
  check("panel 本身未被当成导航列", !q(dom, "panel").hasAttribute(NAV_ATTR));
  check("content 被标记为内容列", q(dom, "content").hasAttribute(CONTENT_ATTR));
  check("mask 未被当成内容列", !q(dom, "mask").hasAttribute(CONTENT_ATTR));
  // 关键：导航必须保持可见（v1.2.179 就是被 display:none 隐藏了）
  const css = styleEl(dom)?.textContent ?? "";
  check("CSS 不隐藏首个子元素（避免误藏导航）", !/> :first-child\s*\{[^}]*display:\s*none/.test(css));
  dispose();
}

/* ⑨ 关键回归：主界面框架绝不能被误判（v1.2.178 真机黑屏事故） */
console.log("\n=== ⑨ 关键回归：主界面框架（侧栏多按钮 + 大对话区）不得被误判 ===");
{
  // 真机事故结构：AppFrame 网格里 sidebarCol 含很多按钮（新会话/插件/设置/
  // 会话项…），centerCol 是很大的兄弟节点 —— 只看「按钮多 + 有大兄弟」的旧判据
  // 完全命中它，会把主界面改成竖向堆叠 → 黑屏空白（真机实测）。
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>
      <div id="frame" style="display:grid;grid-template-columns:280px 1fr;height:100vh">
        <div id="sidebarCol" style="position:relative">
          <button>新会话</button><button>插件</button>
          <button>会话一</button><button>会话二</button>
          <button>设置</button>
        </div>
        <div id="centerCol" style="position:relative">对话内容</div>
      </div></body></html>`,
    { pretendToBeVisual: true, runScripts: "outside-only", url: "http://127.0.0.1:3080/" },
  );
  const win = dom.window;
  Object.defineProperty(win, "innerWidth", { value: 386, writable: true, configurable: true });
  Object.defineProperty(win, "innerHeight", { value: 777, writable: true, configurable: true });
  const frame = win.document.getElementById("frame");
  const sidebarCol = win.document.getElementById("sidebarCol");
  const centerCol = win.document.getElementById("centerCol");
  frame.getBoundingClientRect = () => ({ width: 386, height: 777, top: 0, left: 0, right: 386, bottom: 777 });
  sidebarCol.getBoundingClientRect = () => ({ width: 280, height: 777, top: 0, left: 0, right: 280, bottom: 777 });
  centerCol.getBoundingClientRect = () => ({ width: 106, height: 777, top: 0, left: 280, right: 386, bottom: 777 });

  const { dispose } = activate(dom);
  check("主界面框架未被标记", !frame.hasAttribute(PANEL_ATTR));
  check("主界面侧栏未被标记", !sidebarCol.hasAttribute(NAV_ATTR));
  check("主界面对话区未被标记", !centerCol.hasAttribute(CONTENT_ATTR));
  check("无样式表注入（界面保持原样）", !styleEl(dom));
  dispose();
}

/* ⑩ 关键回归：设置面板必须仍能被识别（两道硬门槛没把正常场景挡掉） */
console.log("\n=== ⑩ 关键回归：带关闭按钮的浮层面板仍正常接管 ===");
{
  const dom = buildSettings({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  check("面板被接管（关闭按钮判据未误伤）", q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("导航列被标记", q(dom, "nav").hasAttribute(NAV_ATTR));
  dispose();
}

console.log("通过 " + pass + " / 失败 " + fail);
process.exit(fail === 0 ? 0 : 1);
