/**
 * [dsh-android] narrow-sidebar 插件行为验证（v1.2.175）。
 *
 * 用 jsdom 风格的 DOM 桩复刻引擎的真实结构（AppFrame 网格三列），逐场景验证：
 *   ① 竖屏 + 侧栏展开 → 接管（加 MARK_ATTR + 隐藏中心列）
 *   ② 竖屏 + 侧栏折叠 → 不接管
 *   ③ 横屏（宽 > 900 且宽 > 高）→ 不接管
 *   ④ 竖屏 → 横屏切换 → 自动恢复
 *   ⑤ dispose → 零残留（属性与样式表都清掉）
 *
 * 运行：node _test_narrow_sidebar.mjs
 */

import { readFileSync } from "node:fs";

/* ── 最小 DOM 实现（够本插件用） ────────────────────────────── */

class El {
  constructor(tag, attrs = {}) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.parentElement = null;
    this.attrs = { ...attrs };
    this.style = {};
    this.id = attrs.id || "";
    this.textContent = "";
  }
  get childNodes() { return this.children; }
  /** 真实 DOM 里 parentNode 与 parentElement 同时存在，桩里同步别名。 */
  get parentNode() { return this.parentElement; }
  get nextElementSibling() {
    if (!this.parentElement) return null;
    const i = this.parentElement.children.indexOf(this);
    return i >= 0 ? this.parentElement.children[i + 1] ?? null : null;
  }
  setAttribute(k, v) { this.attrs[k] = String(v); if (k === "id") this.id = String(v); }
  getAttribute(k) { return this.attrs[k] ?? null; }
  hasAttribute(k) { return k in this.attrs; }
  removeAttribute(k) { delete this.attrs[k]; }
  appendChild(c) { c.parentElement = this; this.children.push(c); return c; }
  removeChild(c) {
    const i = this.children.indexOf(c);
    if (i >= 0) this.children.splice(i, 1);
    c.parentElement = null;
    return c;
  }
  querySelector(sel) { return query(this, sel); }
  querySelectorAll(sel) { const r = []; collect(this, sel, r); return r; }
}

/** 支持 `[data-android-narrow]`、`[aria-label="x"]`、`#id`、`*:nth-child(n)` 的最小子集。 */
function matches(el, sel) {
  sel = sel.trim();
  if (sel.startsWith("[") && sel.endsWith("]")) {
    const body = sel.slice(1, -1);
    const eq = body.indexOf("=");
    if (eq < 0) return el.hasAttribute(body);
    const k = body.slice(0, eq);
    const v = body.slice(eq + 1).replace(/^["']|["']$/g, "");
    return el.getAttribute(k) === v;
  }
  if (sel.startsWith("#")) return el.id === sel.slice(1);
  if (sel === "*") return true;
  return el.tagName === sel.toUpperCase();
}

function query(root, sel) {
  for (const c of root.children) {
    if (matches(c, sel)) return c;
    const deep = query(c, sel);
    if (deep) return deep;
  }
  return null;
}

function collect(root, sel, out) {
  for (const c of root.children) {
    if (matches(c, sel)) out.push(c);
    collect(c, sel, out);
  }
}

/* ── 复刻引擎结构：grid > sidebarCol(内含折叠按钮) + centerCol ── */

function buildEngineDom({ width, height, expanded, extraSiblings = false }) {
  const doc = new El("html");
  const head = new El("head");
  const body = new El("body");
  doc.appendChild(head);
  doc.appendChild(body);

  const grid = new El("div", { class: "pI_x6G_frame" });
  grid.computedDisplay = "grid";
  body.appendChild(grid);

  // 引擎在网格里还插了 DocumentTitle（不渲染 DOM）、DragHandle 等兄弟节点。
  // extraSiblings=true 时插入无关兄弟，用来验证插件不靠 :first-child/:nth-child 猜位置。
  if (extraSiblings) {
    const overlay = new El("div", { class: "pI_x6G_overlayLayer" });
    grid.appendChild(overlay);
  }

  const sidebarCol = new El("div", { class: "pI_x6G_sidebarCol" });
  grid.appendChild(sidebarCol);

  // 展开态：侧栏里有"收起侧边栏"按钮；折叠态：按钮换成 rail（无该标签）
  const toggle = new El("button", expanded ? { "aria-label": "收起侧边栏" } : {});
  sidebarCol.appendChild(toggle);

  const centerCol = new El("div", { class: "pI_x6G_centerCol" });
  grid.appendChild(centerCol);

  if (extraSiblings) {
    const rightbarCol = new El("div", { class: "pI_x6G_rightbarCol" });
    grid.appendChild(rightbarCol);
  }

  return { doc, head, body, grid, sidebarCol, centerCol };
}

/* ── 可控的 window/全局环境 ──────────────────────────────────── */

function makeEnv(dom, viewport) {
  const listeners = {};
  const win = {
    innerWidth: viewport.width,
    innerHeight: viewport.height,
    requestAnimationFrame: (fn) => { fn(); return 1; },
    cancelAnimationFrame: () => {},
    setTimeout: () => 0,
    clearTimeout: () => {},
    addEventListener: (t, fn) => { (listeners[t] ||= []).push(fn); },
    removeEventListener: (t, fn) => {
      listeners[t] = (listeners[t] || []).filter((f) => f !== fn);
    },
    getComputedStyle: (el) => ({ display: el.computedDisplay || "block" }),
    __ModuleLoader__: { load: () => {} },
  };
  global.window = win;
  global.document = {
    head: dom.head,
    documentElement: dom.doc,
    body: dom.body,
    querySelector: (s) => query(dom.doc, s),
    querySelectorAll: (s) => { const r = []; collect(dom.doc, s, r); return r; },
    getElementById: (id) => query(dom.doc, "#" + id),
    createElement: (tag) => new El(tag),
  };
  global.MutationObserver = class { constructor() {} observe() {} disconnect() {} };
  return { win, listeners };
}

/* ── 载入插件（经典 script 方式） ─────────────────────────────── */

function loadPlugin() {
  let reg = null;
  global.window = { __ModuleLoader__: { load: (r) => { reg = r; } } };
  const code = readFileSync(
    new URL("../../app/src/main/assets/android-plugins/narrow-sidebar/client.js", import.meta.url),
    "utf8",
  );
  (0, eval)(code);
  return reg;
}

/* ── 场景断言 ───────────────────────────────────────────────── */

const SB_ATTR = "data-android-narrow-sidebar-col";
const YIELD_ATTR = "data-android-narrow-yield-col";
const STYLE_ID = "dsh-android-narrow-sidebar-style";
let pass = 0, fail = 0;

function check(label, cond) {
  if (cond) { console.log("   ✓ " + label); pass++; }
  else { console.log("   ✗ " + label); fail++; }
}

const reg = loadPlugin();
console.log("注册 id =", JSON.stringify(reg.id));
check("注册 id 等于包名", reg.id === "dsh-android-narrow-sidebar");

function run(label, { viewport, expanded, expectMarked, extraSiblings = false }) {
  console.log("\n=== " + label + " ===");
  const dom = buildEngineDom({ ...viewport, expanded, extraSiblings });
  const { win } = makeEnv(dom, viewport);
  const mod = reg.factory(() => { throw new Error("不应 require 模块"); });
  const dispose = mod.apply({});
  console.log("   视口 " + viewport.width + "x" + viewport.height +
              " 侧栏=" + (expanded ? "展开" : "折叠"));
  check("侧栏列 " + (expectMarked ? "已接管" : "未接管"),
        dom.sidebarCol.hasAttribute(SB_ATTR) === expectMarked);
  if (expectMarked) {
    check("样式表已注入", !!dom.head.querySelector("#" + STYLE_ID));
    const css = dom.head.querySelector("#" + STYLE_ID).textContent;
    check("CSS 含 grid-column 跨行规则", /grid-column:\s*1\s*\/\s*-1/.test(css));
    check("CSS 含隐藏对话列规则", /display:\s*none/.test(css));
    check("CSS 全部以本插件属性为前缀",
          css.split("\n").filter((l) => l.trim() && !l.trim().startsWith("/*"))
             .every((l) => l.includes("data-android-narrow")));
    check("中心列已让位", dom.centerCol.hasAttribute(YIELD_ATTR));
  } else {
    check("中心列未让位", !dom.centerCol.hasAttribute(YIELD_ATTR));
  }
  return { dom, win, dispose, mod };
}

// ① 用户的实际场景：手机竖屏 + 侧栏展开 → 必须接管
const t1 = run("① 手机竖屏 405x880 + 侧栏展开（用户场景）",
               { viewport: { width: 405, height: 880 }, expanded: true, expectMarked: true });

// ② 竖屏但侧栏折叠 → 不接管（保持引擎的图标栏）
run("② 手机竖屏 405x880 + 侧栏折叠",
    { viewport: { width: 405, height: 880 }, expanded: false, expectMarked: false });

// ③ 横屏（手机横屏 880x405）→ 不接管
run("③ 手机横屏 880x405（宽 > 高）",
    { viewport: { width: 880, height: 405 }, expanded: true, expectMarked: false });

// ④ 宽屏（桌面模式 1280x800）→ 不接管
run("④ 桌面/宽屏 1280x800 + 侧栏展开",
    { viewport: { width: 1280, height: 800 }, expanded: true, expectMarked: false });

// ⑤ 竖屏 → 横屏切换 → 自动恢复
console.log("\n=== ⑤ 竖屏接管后转横屏 → 应自动恢复 ===");
{
  const dom = buildEngineDom({ width: 405, height: 880, expanded: true });
  const { win, listeners } = makeEnv(dom, { width: 405, height: 880 });
  const mod = reg.factory(() => {});
  const dispose = mod.apply({});
  check("初始已接管", dom.sidebarCol.hasAttribute(SB_ATTR));
  // 模拟转屏：改视口尺寸并触发 resize
  win.innerWidth = 880;
  win.innerHeight = 405;
  (listeners["resize"] || []).forEach((fn) => fn());
  check("转横屏后已恢复（标记移除）", !dom.sidebarCol.hasAttribute(SB_ATTR));
  check("转横屏后中心列恢复", !dom.centerCol.hasAttribute(YIELD_ATTR));
  check("转横屏后样式表已移除", !dom.head.querySelector("#" + STYLE_ID));
  // 转回竖屏 → 应再次接管
  win.innerWidth = 405;
  win.innerHeight = 880;
  (listeners["resize"] || []).forEach((fn) => fn());
  check("转回竖屏后再次接管", dom.sidebarCol.hasAttribute(SB_ATTR));
  dispose();
}

// ⑥ dispose 零残留
console.log("\n=== ⑥ dispose 零残留 ===");
{
  const dom = buildEngineDom({ width: 405, height: 880, expanded: true });
  makeEnv(dom, { width: 405, height: 880 });
  const mod = reg.factory(() => {});
  const dispose = mod.apply({});
  check("接管中", dom.sidebarCol.hasAttribute(SB_ATTR));
  dispose();
  check("dispose 后标记已移除", !dom.sidebarCol.hasAttribute(SB_ATTR));
  check("dispose 后让位列已恢复", !dom.centerCol.hasAttribute(YIELD_ATTR));
  check("dispose 后样式表已移除", !dom.head.querySelector("#" + STYLE_ID));
}

// ⑦ 回归：DOM 里混入无关兄弟节点时，仍能正确定位（不靠 :first-child 猜位置）
console.log("\n=== ⑦ 回归：网格里有无关兄弟节点（overlay/右栏）===");
{
  const dom = buildEngineDom({ width: 405, height: 880, expanded: true, extraSiblings: true });
  makeEnv(dom, { width: 405, height: 880 });
  const mod = reg.factory(() => {});
  const dispose = mod.apply({});
  check("侧栏列被正确定位并接管", dom.sidebarCol.hasAttribute(SB_ATTR));
  check("中心列让位", dom.centerCol.hasAttribute(YIELD_ATTR));
  check("右栏也让位（不在窄屏挤占）",
        dom.grid.children[dom.grid.children.length - 1].hasAttribute(YIELD_ATTR));
  check("overlay 未被误标",
        !dom.grid.children[0].hasAttribute(SB_ATTR) &&
        !dom.grid.children[0].hasAttribute(YIELD_ATTR));
  dispose();
}

// ⑧ 回归：侧栏被引擎重建（换新节点）时，标记迁移到新节点
console.log("\n=== ⑧ 回归：引擎重建侧栏列 → 标记迁移 ===");
{
  const dom = buildEngineDom({ width: 405, height: 880, expanded: true });
  const { win, listeners } = makeEnv(dom, { width: 405, height: 880 });
  const mod = reg.factory(() => {});
  const dispose = mod.apply({});
  check("初始接管旧侧栏", dom.sidebarCol.hasAttribute(SB_ATTR));
  // 模拟 React 重建：替换 sidebarCol 为新节点
  const newSidebar = new El("div", { class: "pI_x6G_sidebarCol" });
  newSidebar.appendChild(new El("button", { "aria-label": "收起侧边栏" }));
  const idx = dom.grid.children.indexOf(dom.sidebarCol);
  dom.grid.children[idx] = newSidebar;
  newSidebar.parentElement = dom.grid;
  dom.sidebarCol.parentElement = null;
  (listeners["resize"] || []).forEach((fn) => fn());
  check("新侧栏列被接管", newSidebar.hasAttribute(SB_ATTR));
  check("旧侧栏列标记已清除", !dom.sidebarCol.hasAttribute(SB_ATTR));
  check("中心列仍让位", dom.centerCol.hasAttribute(YIELD_ATTR));
  dispose();
}
t1.dispose();

console.log("\n────────────────────────────");
console.log("通过 " + pass + " / 失败 " + fail);
process.exit(fail === 0 ? 0 : 1);
