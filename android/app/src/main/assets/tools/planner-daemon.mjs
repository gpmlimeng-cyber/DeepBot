#!/usr/bin/env node
/**
 * [dsh-android] 规划守护进程（planner-daemon）—— 「边规划边行动」的常驻执行体。
 *
 * ## 为什么需要它（用户需求）
 * 对话流是「请求-响应」模型：主 AI 推理一轮 → 调工具 → 拿结果 → 再推理。
 * 推理期间无法执行工具，所以「边想边做」在对话流里天然做不到。本进程
 * **独立于对话轮**常驻运行：主 AI 只需把「任务 + 成功条件」委托进来，
 * 剩下的循环（读屏 → 规划 → 本地执行 → 看结果 → 再规划）全部在这里完成。
 *
 * ## 循环（每个任务）
 *   读屏（bridge /screen）
 *     → 规划 AI 决策（DeepSeek API：给计划 或 判定完成）
 *     → 本地闭环执行（bridge /loop/run：PICK/ACT/VERIFY/RETRY）
 *     → 结果入历史 → 回到读屏
 *   直到 done / 步数上限 / 预算耗尽 / 被停止。
 *
 * ## 通信（文件队列，无需端口）
 *   任务投递：$DSH_HOME/tasks/queue/<id>.json   （由 gates/parallel 写入）
 *   状态/结果：$DSH_HOME/tasks/<id>.json        （本进程维护，原子写）
 *   日志：    $DSH_HOME/tasks/<id>.log
 *
 * ## 环境
 *   DSH_BRIDGE_TOKEN  桥认证（由 app 的 shell-env 注册注入）
 *   DSH_HOME          任务/凭证目录
 *   凭证 key：$DSH_HOME/.credentials.yaml 的 refs 段（max+ 开关授权范围内）
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, renameSync, appendFileSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

const HOME = process.env.DSH_HOME || '/data/user/0/app.dsh.mobile/files/dsh-home';
const TOKEN = process.env.DSH_BRIDGE_TOKEN || '';
const BRIDGE = `http://127.0.0.1:${process.env.DSH_BRIDGE_PORT || 3083}`;
const TASKS = join(HOME, 'tasks');
const QUEUE = join(TASKS, 'queue');
const API_BASE = process.env.DSH_PLANNER_API_BASE || 'https://api.deepseek.com';
const MODEL = process.env.DSH_PLANNER_MODEL || 'deepseek-chat';
/** 首轮规划模型（用户设计：初始规划允许思考——一次性想清整条路径；
 *  执行阶段关思考——快速决策）。只调一次，换来后续轮次大幅减少。 */
const MODEL_THINK = process.env.DSH_PLANNER_THINK_MODEL || 'deepseek-reasoner';
const POLL_MS = 2000;
const MAX_TASK_MS = 10 * 60 * 1000;   // 单任务总预算（硬上限）

for (const d of [TASKS, QUEUE]) { if (!existsSync(d)) mkdirSync(d, { recursive: true }); }

// ---------- 工具 ----------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();

function log(id, msg) {
  const line = `[${new Date().toISOString()}] ${msg}\n`;
  try { appendFileSync(join(TASKS, `${id}.log`), line); } catch {}
  process.stdout.write(line);
}

function saveTask(task) {
  const f = join(TASKS, `${task.id}.json`);
  const tmp = f + '.tmp';
  writeFileSync(tmp, JSON.stringify(task, null, 1));
  renameSync(tmp, f);   // 原子替换：查询方永远读到完整 JSON
}

// ---------- 进度通知（v1.2.159，用户反馈「不知道它做完没」）----------
// 每隔一段时间（或任务结束时）发一条系统通知：已完成什么 / 正在做什么。
// 目的：用户切回对话流时不必猜「它还在跑还是已经完了」。
const NOTIFY_EVERY_MS = 45_000;
/** 通知不参与任务成败：任何异常都吞掉 */
function runCatchingNotify(task, decision) {
  try {
    const last = task.history.length ? task.history[task.history.length - 1] : null;
    const done = last && last.result ? String(last.result).split(' ')[0] : '';
    notifyProgress(task.goal, done, (decision && decision.reason) || '', false);
  } catch (e) { /* ignore */ }
}
let lastNotifyAt = 0;
async function notifyProgress(goal, done, doingNow, force) {
  const t = Date.now();
  if (!force && t - lastNotifyAt < NOTIFY_EVERY_MS) return;
  lastNotifyAt = t;
  const body = ((done ? '已完成：' + done : '') + (doingNow ? ' + String.fromCharCode(10) + ' + '正在做：' + doingNow : '')).slice(0, 300);
  try { await bridge('/notify', { title: 'DSH · ' + String(goal || '').slice(0, 30), body }, 8000); } catch (e) { /* 通知失败不影响任务 */ }
}

async function bridge(path, body, timeoutMs = 120000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${BRIDGE}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'X-DSH-Token': TOKEN, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal,
    });
    const text = await res.text();
    // v1.2.152 审查修复：这里此前**从不标记 HTTP 状态**，而下文两处「403 就退出」
    // 的自检判断的是 `result._http === 403` —— 死代码，孤儿守护进程永远自愈不了
    // （App 重启换 token 后，旧进程每轮 403 空转、任务永久挂起）。现在统一挂上。
    if (!res.ok) {
      let parsed = null;
      try { parsed = JSON.parse(text); } catch {}
      if (parsed && typeof parsed === 'object') { parsed._http = res.status; return parsed; }
      return { _http: res.status, _body: text.slice(0, 300) };
    }
    try { return JSON.parse(text); } catch { return text; }   // /screen 返回紧凑文本
  } catch (e) {
    return { _http: 0, _error: String(e && e.message || e) };
  } finally { clearTimeout(timer); }
}

/** 从 .credentials.yaml 的 refs 段读 API key（只取需要的那个 ref 的值） */
function readApiKey(ref = 'DEEPSEEK_API_KEY') {
  const f = join(HOME, '.credentials.yaml');
  if (!existsSync(f)) return '';
  const lines = readFileSync(f, 'utf8').split('\n');
  let inRefs = false;
  for (const raw of lines) {
    const line = raw.trimEnd();
    if (!line.startsWith(' ') && line.endsWith(':')) { inRefs = line.trim() === 'refs:'; continue; }
    if (inRefs && line.startsWith('  ') && !line.startsWith('    ')) {
      const name = line.trim().split(':')[0];
      if (name === ref) return line.trim().slice(name.length + 1).trim();
    }
  }
  return '';
}

// ---------- 任务后复盘（v1.2.157，试验性；DSH_PLANNER_RETRO=0 可关）----------
// 为什么要它：daemon 执行期用的是**无思考**的修复轮模型（省时间），因此它没有机会
// 反思。任务一结束，这里显式换成**思考模型**问一次"这次哪里卡、你不理解什么、
// 建议怎么改"——产物写进任务 JSON 的 retro 字段 + retros/<id>.md，供我们持续改进。
// 纪律：只让它依据 history 说话，不许编造（不知道就写「未知」）。
const RETRO_ON = process.env.DSH_PLANNER_RETRO !== '0';
const NL = String.fromCharCode(10);

const RETRO_SYS = [
  '你是刚在 Android 手机上跑完一个任务的规划守护进程。请写一份**简短、诚实**的复盘（中文，250 字内），只含三节：',
  '1) 卡点：哪一步最慢/最容易失败，为什么——引用具体 step id 与现象。',
  '2) 不理解：这套工具（loop 的 DSL、scr 命令、verify、记忆、scroll_find/back_to 等）里你还有哪里没搞明白，或提示词没讲清。',
  '3) 建议：最能提速或最不容易出错的 1-3 条**具体**改动（针对工具或提示词，不要泛泛而谈）。',
  '只写 HISTORY 能支撑的内容；不知道就写「未知」。不要客套话。',
].join(NL);

async function retro(task) {
  if (!RETRO_ON) return;
  const key = readApiKey();
  if (!key) return;
  const hist = task.history.slice(-10).map((h) =>
    '- #' + h.n + ' plan=' + JSON.stringify(h.plan).slice(0, 180) + ' => ' + String(h.result).slice(0, 260)).join(NL);
  const user = ['GOAL: ' + task.goal, 'SUCCESS: ' + task.success,
    'FINAL: ' + task.status + ' — ' + (task.reason || ''), 'ROUNDS: ' + task.history.length,
    'HISTORY:', hist || '(none)'].join(NL);
  try {
    const res = await fetch(API_BASE + '/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + key },
      body: JSON.stringify({ model: MODEL_THINK,
        messages: [{ role: 'system', content: RETRO_SYS }, { role: 'user', content: user }], max_tokens: 1400 }),
    });
    const body = await res.json();
    const text = (body && body.choices && body.choices[0] && body.choices[0].message && body.choices[0].message.content) || '';
    if (!text) return;
    task.retro = text;
    log(task.id, 'RETRO:' + NL + text);
    try {
      const dir = join(TASKS, 'retros');
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, task.id + '.md'),
        '# ' + task.goal + NL + NL + '- status: ' + task.status + NL + '- rounds: ' + task.history.length +
        NL + '- reason: ' + (task.reason || '') + NL + NL + text + NL);
    } catch (e) { /* 落盘失败不影响主流程 */ }
  } catch (e) {
    log(task.id, 'retro failed: ' + (e && e.message));
  }
}

// ---------- 规划 AI ----------
/** 最近一次规划调用的用量（v1.2.140 观测：缓存命中/延迟是提速决策的唯一依据） */
let lastUsage = null;
const PLANNER_SYSTEM = `You are the planning brain of a persistent Android UI-automation daemon. You run OUTSIDE the chat loop: you read the screen, decide the next short plan, and the local closed loop executes it. Repeat until the goal is met.

Reply with ONE JSON object, nothing else:
{"done": <bool>, "reason": "<short>", "steps": [ ... ]}

Rules:
- If the current screen already satisfies the success condition -> {"done": true, "reason": "..."} and omit steps.
- Otherwise give 2-4 steps in the local loop DSL. Each step:
  {"id":"s1","target":{"text":["anchor"]},"action":{"type":"tap"},"verify":{"anyText":["..."]},"onFail":{"retry":1,"then":"escalate"}}
- action.type may be: tap | tap_text | swipe | scroll | scroll_find | input | key | launch | intent | set_switch | noop | sleep | collect | scroll_collect | repeat | repeat_until | double_tap | long_press | pinch | drag | back_to.
  Use {"type":"set_switch","value":"on|off"} for toggles (idempotent) and verify with {"toggle":{"label":"...","to":"on|off"}}.
  Use {"type":"scroll_find","text":["label","alias"],"tap":true} to find and open list entries.
  Use {"type":"scroll_collect","maxSwipes":5,"direction":"down"} to scroll a long list/article while collecting all text (it returns the merged text).
  Use {"type":"back_to","package":"<pkg>"} or {"type":"back_to","text":["<anchor>"]} to press BACK until the target is on screen — never chain blind repeated back presses, this stops as soon as it is there.
  Use {"type":"repeat","action":{...},"times":3,"intervalMs":800} for a fixed number of identical actions, and {"type":"repeat_until","action":{...},"verify":{...},"maxTimes":8} to repeat until the state changes (e.g. tapping "load more").
  Use {"type":"long_press","x":...,"y":...} / {"type":"double_tap"} for context menus and zoom; {"type":"pinch","direction":"in|out"} and {"type":"drag","x1":...} only on maps/canvas apps.
  Use {"type":"sleep","ms":1500} only when a fixed wait is unavoidable; prefer verify with {"frameChanged":true} or {"foregroundPkg":"..."} — a fixed sleep blocks on the worst case every time.
  Use {"type":"collect","saveAs":"note.txt"} to dump all on-screen text to a file when the goal asks you to gather/read content.
- Steps may carry an optional "when": {"anyText":["..."]} — the step is SKIPPED (not failed) when the condition is not on screen. Use it for optional pop-ups/dialogs ("if a rating dialog appears, dismiss it") instead of spending a repair round.
- Optional per-step flags: "optimistic": true (intermediate steps pass on a fast sub-200ms check — use for steps whose landing is obvious and harmless to get wrong), and {"action":{"type":"swipe","human":true}} for human-like curved gestures (slightly slower, indistinguishable from a real finger).
  Use {"type":"intent","action":"android.settings.DISPLAY_SETTINGS"} to jump STRAIGHT to a system settings page — WIFI_SETTINGS, BLUETOOTH_SETTINGS, DISPLAY_SETTINGS, SOUND_SETTINGS, BATTERY_SAVER_SETTINGS, APPLICATION_SETTINGS, LOCATION_SOURCE_SETTINGS, SECURITY_SETTINGS, DEVICE_INFO_SETTINGS, INTERNAL_STORAGE_SETTINGS, DATE_SETTINGS, ACCESSIBILITY_SETTINGS. This saves 3-5 steps versus tapping through the settings list; prefer it whenever the goal targets a known settings page. {"type":"launch","package":"com.android.settings"} opens Settings itself.
- Anchors MUST be text actually visible on the screen (copy it exactly, do not translate or guess).
- Prefer [switch]/[row] roles from the dump when deciding what to tap.
- Verify only with text that appears AFTER the action (never text already on the source page).
- If the previous attempts show a repeated failure, change the approach (different anchor, scroll first, dismiss dialog) instead of repeating.
- **STAY ON THE TARGET SCREEN (v1.2.159 fix)**: this task started while START_PKG was the foreground app. Every round, if the current foreground package is NOT START_PKG (navigated away by a link/back/ad, or the app restarted), your FIRST action MUST be to return to START_PKG (re-launch it, or back out to it), then continue. NEVER search for the goal on a different page, and NEVER declare done based on another page's content. If you cannot return after a few tries, mark the task failed and report honestly.
- NEVER repeat work: if the execution history shows a step succeeded and the target state is already visible on the current screen, do not redo it — re-tapping a toggle flips it back (that is why set_switch is idempotent). Each round costs seconds; skip everything already true.
- FIRST ROUND (no history yet): think through the WHOLE task and output a COMPLETE plan of 6-10 steps covering the whole path end-to-end (open app → navigate → handle likely interruptions → reach the goal). Predict plausible intermediate states (loading screens, "no internet" bars, ad pop-ups, permission dialogs) and include steps to handle them. Each round trip costs 1-2 seconds — a complete plan is strictly better than discovering the path one failure at a time.
- REPAIR ROUNDS (history exists): output 2-4 steps that fix only the CURRENT blocker; do not restate the whole path.
- VERIFY THE MINIMUM (v1.2.146): verification must match the NEXT action's precondition, not "everything loaded". After launching an app, \`{"foregroundPkg":"<pkg>"}\` is enough — do NOT wait for page content (measured: waiting for a page title costs 6+ extra seconds while the UI was usable in 1-2 s). Only wait for specific content when the next step actually reads/clicks it.`;

async function askPlanner(task, screen) {
  const key = readApiKey();
  if (!key) return { error: 'no API key in $DSH_HOME/.credentials.yaml refs (enable Accessibility max+ or configure the WebUI)' };
  const recent = task.history.slice(-8).map((h) =>
    `- step ${h.n}: plan=${JSON.stringify(h.plan).slice(0, 300)} => ${h.result}`).join('\n');
  const isFirst = task.history.length === 0;
  const modelForRound = (isFirst && !task.thinkUnavailable) ? MODEL_THINK : MODEL;
  const user = `GOAL: ${task.goal}\nSUCCESS CONDITION: ${task.success}\nSTART_PKG: ${startPkg || '(unknown)'}\n\nROUND: ${isFirst ? 'FIRST — produce the complete 6-10 step plan' : `REPAIR #${task.history.length} — fix only the current blocker (2-4 steps)`}\n\nCURRENT SCREEN:\n${String(screen).slice(0, 3500)}\n\nEXECUTED SO FAR (${task.history.length}):\n${recent || '(none)'}`;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 90000);
  const t0 = Date.now();
  try {
    const res = await fetch(`${API_BASE}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
      body: JSON.stringify({
        model: modelForRound,
        messages: [{ role: 'system', content: PLANNER_SYSTEM }, { role: 'user', content: user }],
        temperature: 0.2,
        response_format: { type: 'json_object' },
      }),
      signal: ctrl.signal,
    });
    if (!res.ok) {
      const body = (await res.text()).slice(0, 200);
      if (isFirst && modelForRound !== MODEL) {
        // 思考模型不可用（权限/命名变更）→ 本任务降级快速模型，不中断
        log(task.id, `think model unavailable (${res.status}); falling back to ${MODEL}`);
        task.thinkUnavailable = true;
      }
      return { error: `planner API ${res.status}: ${body}` };
    }
    const data = await res.json();
    // v1.2.140 用量观测：缓存命中与延迟是"还能不能更快"的唯一事实依据
    // （实测：DeepSeek 前缀缓存命中 96% 可省 ~25% 延迟；PLANNER_SYSTEM 是固定
    //  前缀、user 内容在后 → 命中条件成立。若 cacheHit 长期为 0 说明前缀被破坏。）
    const u = data?.usage || {};
    lastUsage = {
      prompt: u.prompt_tokens ?? 0,
      cacheHit: u.prompt_cache_hit_tokens ?? 0,
      cacheMiss: u.prompt_cache_miss_tokens ?? 0,
      completion: u.completion_tokens ?? 0,
      ms: Date.now() - t0,
    };
    const text = data?.choices?.[0]?.message?.content ?? '';
    const m = text.match(/\{[\s\S]*\}/);
    if (!m) return { error: `planner returned no JSON: ${text.slice(0, 200)}` };
    return JSON.parse(m[0]);
  } catch (e) {
    return { error: `planner call failed: ${e.message}` };
  } finally { clearTimeout(timer); }
}

// ---------- 任务循环 ----------
async function runTask(task) {
  task.status = 'running';
  task.startedAt = now();
  saveTask(task);
  log(task.id, `task started: goal="${task.goal}" success="${task.success}" maxSteps=${task.maxSteps}`);

  // v1.2.159 目标锚点：任务从哪个前台应用开始，就要求每一步都在它上面执行
  const startPkg = (await bridge('/interference')).pkg || '';
  if (startPkg) log(task.id, 'target anchor: ' + startPkg);
  let screenFails = 0;
  // v1.2.157 用户反馈修复：daemon 做错事会执着重试、永不主动停。
  // 连续失败轮数 ≥3 → 停止并标记 needsHelp，交给（带思考的）对话流 AI 接手：
  // 悬浮窗轮询 /daemon/status 看到 needsHelp 会提示用户，用户在对话里让 AI
  // 查看 parallel status/log 即可接手（AI 有思考，能看失败证据后给出新路径）。
  let consecutiveFails = 0;
  while (task.history.length < task.maxSteps) {
    if (now() - task.startedAt > MAX_TASK_MS) { task.status = 'failed'; task.reason = 'task budget exceeded'; break; }
    if (task.stopRequested || existsSync(join(TASKS, `${task.id}.stop`))) {
      task.status = 'stopped'; task.reason = 'stop requested'; break;
    }

    const screen = await bridge('/screen');
    // v1.2.140：**每轮**检测 token 失效（不仅是启动时）——app 重启会换 token，
    // 而旧 daemon 可能作为孤儿继续存活、gate 又因"它在跑"而不重启新的 →
    // 任务全部 403 空转（实测）。遇到 403 立即退出，让 ensure_daemon 用新 token 拉起。
    if (typeof screen === 'object' && screen !== null && screen._http === 403) {
      log(task.id, 'bridge 403 (stale token after app restart) — exiting so the gate relaunches');
      process.exit(0);
    }
    if (typeof screen !== 'string' || screen.length === 0) {
      // 快速失败（v1.2.136 实测教训）：无障碍服务被系统停用时 /screen 会一直
      // 返回 503，此前这里无限重试 → 任务永远挂着（实测 7 分钟 0 步）。
      // 连续 6 次（~15 秒）仍读不到屏 → 明确失败并给出原因，让委托方立刻知道
      // 是环境问题（服务未启用）而不是任务本身卡住。
      screenFails++;
      if (screenFails >= 6) {
        task.status = 'failed';
        task.reason = `screen unreadable after ${screenFails} tries: ${JSON.stringify(screen).slice(0, 150)}`;
        task.finishedAt = now();
        saveTask(task);
        log(task.id, `FAILED: ${task.reason}`);
    notifyProgress(task.goal, '任务失败：' + (task.reason || ''), '', true);
        return;
      }
      log(task.id, `screen read failed (${screenFails}/6): ${JSON.stringify(screen).slice(0, 120)}`);
      await sleep(2500);
      continue;
    }
    screenFails = 0;

    const decision = await askPlanner(task, screen);
    if (decision.error) { log(task.id, `planner error: ${decision.error}`); await sleep(3000); continue; }
    const u = lastUsage;
    log(task.id, `planner: done=${!!decision.done} reason="${(decision.reason || '').slice(0, 80)}"` +
      (u ? ` [usage: prompt=${u.prompt} cacheHit=${u.cacheHit} miss=${u.cacheMiss} out=${u.completion} ${u.ms}ms]` : ''));

    // v1.2.159：把「上轮结果 + 本轮意图」推成系统通知（内部自带 45s 节流）
    runCatchingNotify(task, decision);

    if (decision.done) {
      task.status = 'done';
      task.reason = decision.reason || 'planner confirmed success';
      task.finishedAt = now();
      saveTask(task);
      log(task.id, `DONE: ${task.reason}`);
      notifyProgress(task.goal, '任务完成：' + (task.reason || ''), '', true);
      await retro(task);
      saveTask(task);
      return;
    }
    const steps = Array.isArray(decision.steps) ? decision.steps : [];
    if (steps.length === 0) { log(task.id, 'planner returned no steps; waiting for screen change'); await sleep(3000); continue; }

    const plan = {
      goal: task.goal, steps,
      useMemory: false,            // 守护进程自行跟踪进度，记忆会整份替换 steps 干扰协调
      waitSliceMs: 3000,
      useScreenEvents: false,      // 事件摘要留给主 AI；本地仍自适应读屏
    };
    const result = await bridge('/loop/run', plan, 180000);
    const n = task.history.length + 1;
    const summary = typeof result === 'object'
      ? `ok=${result.ok} escalated=${result.escalated} failedAt=${result.failedAt ?? '-'} steps=${JSON.stringify((result.steps || []).map((s) => s.status))}` +
        // v1.2.152 审查修复：升级证据此前被整段丢弃——规划 AI 只看到「第 3 步失败」，
        // 看不到失败帧（屏幕上实际有什么）和候选池（本地差点点成哪个），于是盲目
        // 重规划、重复同一失败。现在把这两项一并送进历史。
        (result.escalated && result.failScreen
          ? `
  FAILED SCREEN (what is actually there): ${String(result.failScreen).slice(0, 1200)}` : '') +
        ((result.steps || []).filter((s) => (s.candidates || []).length)
          .map((s) => `
  CANDIDATES for step ${s.id}: ${JSON.stringify(s.candidates).slice(0, 500)}`).join('')) +
        ((result.steps || []).filter((s) => s.error)
          .map((s) => `
  ERROR at step ${s.id}: ${String(s.error).slice(0, 200)}`).join(''))
      : `non-json: ${String(result).slice(0, 120)}`;
    task.history.push({ n, plan: steps, result: summary });
    saveTask(task);
    log(task.id, `executed #${n}: ${summary}`);

    // 失败升级时给规划 AI 更多证据（失败帧在 result 里，已被 history 携带）
    if (typeof result === 'object' && (result.escalated || result.ok === false)) {
      consecutiveFails++;
      await sleep(1500);   // 给界面一点时间稳定，再读屏重新规划
      if (consecutiveFails >= 3) {
        // 连续 3 轮都失败：不再执着。停止并交给对话流 AI（用户反馈：它此前会一直
        // 反复试、既不成功也不停，用户还无法主动取消）。
        task.status = 'failed';
        task.needsHelp = true;
        task.reason = '3 consecutive failed rounds — handing back to the conversation AI for help';
        task.finishedAt = now();
        saveTask(task);
        log(task.id, `NEEDS-HELP: ${task.reason}`);
        notifyProgress(task.goal, '卡住了，需要你介入（连续 3 轮失败）', '', true);
        return;
      }
    } else {
      consecutiveFails = 0;
    }
  }

  if (task.status === 'running') {
    task.status = 'failed';
    task.reason = `step limit reached (${task.maxSteps}) without confirming success`;
    task.finishedAt = now();
    saveTask(task);
    log(task.id, `FAILED: ${task.reason}`);
    await retro(task);
    saveTask(task);
  }
}

async function pickQueued() {
  let files = [];
  try { files = readdirSync(QUEUE).filter((f) => f.endsWith('.json')); } catch { return null; }
  if (files.length === 0) return null;
  const f = join(QUEUE, files.sort()[0]);
  try {
    const task = JSON.parse(readFileSync(f, 'utf8'));
    unlinkSync(f);                                  // 出队（先删，避免重复执行）
    task.id = task.id || files[0].replace(/\.json$/, '');
    task.history = task.history || [];
    task.maxSteps = task.maxSteps || 15;
    saveTask(task);
    return task;
  } catch (e) {
    try { unlinkSync(f); } catch {}
    return null;
  }
}

// ---------- 主循环 ----------
log('daemon', `planner-daemon up: bridge=${BRIDGE} home=${HOME} model=${MODEL} apiKey=${readApiKey() ? 'present' : 'MISSING'}`);

// 启动自检（v1.2.137 实测教训）：app 重启会换 bridge token，而**旧 daemon 进程
// 可能作为孤儿继续存活**（它的环境里是旧 token）→ 所有调用 403、任务空转。
// 这里主动探一次，403 立即退出，让 gates/parallel 的 ensure_daemon 用新 token 拉起。
{
  const probe = await bridge('/screen');
  if (typeof probe === 'object' && probe !== null && probe._http === 403) {
    log('daemon', 'bridge 403 (stale token after app restart) — exiting so the gate can relaunch with a fresh token');
    process.exit(0);
  }
}

while (true) {
  try {
    const task = await pickQueued();
    if (!task) { await sleep(POLL_MS); continue; }
    await runTask(task);
  } catch (e) {
    log('daemon', `loop error: ${e.message}`);
    await sleep(3000);
  }
}
