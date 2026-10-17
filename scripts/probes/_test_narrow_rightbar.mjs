/**
 * [dsh-android] narrow-rightbar 插件验证（v1.2.181）。
 *
 * 复刻引擎 `dsh-client-ui-sidebar-right` 的真实 DOM（结构取自其 client.js）：
 *
 *   <div data-sidebar-right-panel="fullscreen|push"
 *        data-sidebar-right-open            ← 仅打开时存在
 *        style="width:100vw; --dsh-sidebar-width:100vw">
 *     <div class="panelBody">
 *       <div data-dockkit-host="dock">…</div>
 *
 * 关键验证：
 *   ① 窄屏 + 面板打开 → 接管（面板/样式表）
 *   ② 窄屏 + 面板关闭（无 open 属性）→ **绝不接管**（防止凭空冒出面板）
 *   ③ 宽屏（>900）+ 打开 → 不接管（保留引擎分栏）
 *   ④ 关闭面板 → 自动撤销
 *   ⑤ 转屏：窄 → 宽 → 窄
 *   ⑥ dispose 零残留
 *   ⑦ 关键回归：关闭态面板的 dock 保持引擎原样（不强制 visible）
 *   ⑧ 关键回归：面板重建（切会话）→ 标记迁移
 *
 * 运行：node _test_narrow_rightbar.mjs
 */

import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

const PLUGIN = new URL(
  "../../app/src/main/assets/android-plugins/narrow-rightbar/client.js",
  import.meta.url,
);
const PKG_NAME = "dsh-android-narrow-rightbar";
const PANEL_ATTR = "data-android-narrow-rightbar";
const STYLE_ID = "dsh-android-narrow-rightbar-style";

let pass = 0, fail = 0;
const check = (label, cond) => {
  if (cond) { console.log("   ✓ " + label); pass++; }
  else { console.log("   ✗ " + label); fail++; }
};

/**
 * 构造引擎右栏面板 DOM。
 * @param {{width:number, height:number, open:boolean, mode?:string}} opts
 */
function buildRightbar({ width, height, open, mode = "fullscreen" }) {
  const openAttr = open ? ' data-sidebar-right-open=""' : "";
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>
      <div id="frame" style="display:grid;grid-template-columns:56px 1fr 0px;height:100vh">
        <div id="sidebarCol"></div>
        <div id="centerCol">对话内容</div>
        <div id="rightbarCol" style="position:relative;overflow:visible">
          <div id="panel" data-sidebar-right-panel="${mode}"${openAttr}
               aria-hidden="${open ? "false" : "true"}"
               style="width:100vw;--dsh-sidebar-width:100vw;position:absolute;right:0;top:0;bottom:0">
            <div class="panelBody">
              <div id="dock" data-dockkit-host="dock" style="visibility:${open ? "visible" : "hidden"};transform:${open ? "none" : "translateX(100vw)"}">
                <div data-dockkit-column="0" data-dockkit-pane>代码差异内容</div>
              </div>
            </div>
          </div>
        </div>
      </div></body></html>`,
    { pretendToBeVisual: true, runScripts: "outside-only", url: "http://127.0.0.1:3080/" },
  );
  const win = dom.window;
  Object.defineProperty(win, "innerWidth", { value: width, writable: true, configurable: true });
  Object.defineProperty(win, "innerHeight", { value: height, writable: true, configurable: true });
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

/* ① 窄屏 + 打开 → 接管 */
console.log("\n=== ① 窄屏 386x777 + 右栏打开（Issue #16 场景）===");
{
  const dom = buildRightbar({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  check("面板已标记", q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("样式表已注入", !!styleEl(dom));
  const css = styleEl(dom)?.textContent ?? "";
  check("面板改整屏固定定位", /position:\s*fixed\s*!important/.test(css) && /inset:\s*0\s*!important/.test(css));
  check("宽度 100vw", /width:\s*100vw\s*!important/.test(css));
  check("归零 --dsh-sidebar-width（抵消滑出位移）", /--dsh-sidebar-width:\s*0px\s*!important/.test(css));
  check("强制 dock 可见", /visibility:\s*visible\s*!important/.test(css));
  check("强制取消 translateX", /transform:\s*none\s*!important/.test(css));
  check("所有选择器都带本插件属性前缀",
        css.split("\n").filter((l) => l.trim() && !l.trim().startsWith("/*") && !/^[a-z-]+:/.test(l.trim()) && !l.trim().startsWith("}"))
           .every((l) => l.includes("data-android-narrow-rightbar")));
  dispose();
  check("dispose 后标记清除", !q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("dispose 后样式表移除", !styleEl(dom));
}

/* ② 窄屏 + 关闭 → 绝不接管（最重要：防止凭空冒出面板） */
console.log("\n=== ② 窄屏 + 右栏关闭 → 绝不接管 ===");
{
  const dom = buildRightbar({ width: 386, height: 777, open: false });
  const { dispose } = activate(dom);
  check("面板未被标记", !q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("无样式表注入", !styleEl(dom));
  check("dock 保持引擎原样（visibility:hidden）",
        q(dom, "dock").style.visibility === "hidden");
  check("dock 保持引擎原样（translateX 位移）",
        q(dom, "dock").style.transform === "translateX(100vw)");
  dispose();
}

/* ③ 宽屏 + 打开 → 不接管 */
console.log("\n=== ③ 宽屏 1280x800 + 右栏打开 → 不接管（保留分栏）===");
{
  const dom = buildRightbar({ width: 1280, height: 800, open: true });
  const { dispose } = activate(dom);
  check("面板未被标记", !q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("无样式表注入", !styleEl(dom));
  dispose();
}

/* ④ 打开 → 关闭 → 自动撤销 */
console.log("\n=== ④ 打开后关闭面板 → 自动撤销 ===");
{
  const dom = buildRightbar({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  check("初始已接管", q(dom, "panel").hasAttribute(PANEL_ATTR));
  // 模拟引擎关闭：移除 open 属性
  q(dom, "panel").removeAttribute("data-sidebar-right-open");
  await new Promise((r) => dom.window.setTimeout(r, 60));
  check("关闭后标记移除", !q(dom, "panel").hasAttribute(PANEL_ATTR));
  check("关闭后样式表移除", !styleEl(dom));
  dispose();
}

/* ⑤ 转屏：窄 → 宽 → 窄 */
console.log("\n=== ⑤ 转屏：窄屏接管 → 宽屏恢复 → 再窄屏接管 ===");
{
  const dom = buildRightbar({ width: 386, height: 777, open: true });
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

/* ⑥ 回归：面板重建（切会话）→ 标记迁移 */
console.log("\n=== ⑥ 回归：面板被重建 → 标记迁移到新节点 ===");
{
  const dom = buildRightbar({ width: 386, height: 777, open: true });
  const { dispose } = activate(dom);
  const oldPanel = q(dom, "panel");
  check("旧面板已接管", oldPanel.hasAttribute(PANEL_ATTR));

  // 模拟 React 重建：替换为新面板节点
  const newPanel = dom.window.document.createElement("div");
  newPanel.setAttribute("data-sidebar-right-panel", "fullscreen");
  newPanel.setAttribute("data-sidebar-right-open", "");
  const body = dom.window.document.createElement("div");
  body.className = "panelBody";
  const dock = dom.window.document.createElement("div");
  dock.setAttribute("data-dockkit-host", "dock");
  body.appendChild(dock);
  newPanel.appendChild(body);
  oldPanel.parentElement.appendChild(newPanel);
  oldPanel.parentElement.removeChild(oldPanel);

  dom.window.dispatchEvent(new dom.window.Event("resize"));
  check("新面板被接管", newPanel.hasAttribute(PANEL_ATTR));
  check("旧面板标记已清除", !oldPanel.hasAttribute(PANEL_ATTR));
  dispose();
}

/* ⑦ 回归：主界面框架完全不受影响 */
console.log("\n=== ⑦ 回归：无右栏面板的主界面不受影响 ===");
{
  const dom = new JSDOM(
    `<!DOCTYPE html><html><head></head><body>
      <div id="frame" style="display:grid;grid-template-columns:280px 1fr;height:100vh">
        <div id="sidebarCol"><button>新会话</button><button>插件</button><button>设置</button></div>
        <div id="centerCol">对话</div>
      </div></body></html>`,
    { pretendToBeVisual: true, runScripts: "outside-only", url: "http://127.0.0.1:3080/" },
  );
  const win = dom.window;
  Object.defineProperty(win, "innerWidth", { value: 386, writable: true, configurable: true });
  Object.defineProperty(win, "innerHeight", { value: 777, writable: true, configurable: true });
  const { dispose } = activate(dom);
  check("无样式表注入", !styleEl(dom));
  check("框架未被标记", !q(dom, "frame").hasAttribute(PANEL_ATTR));
  dispose();
}

console.log("\n────────────────────────────");
console.log("通过 " + pass + " / 失败 " + fail);
process.exit(fail === 0 ? 0 : 1);
