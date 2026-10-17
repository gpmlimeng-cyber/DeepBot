/**
 * [dsh-android] 窄屏设置面板修复 —— 宿主端（v1.2.178）。
 *
 * 与 `narrow-sidebar` 同为纯 UI 客户端插件：宿主侧必须存在一条可 import 的
 * Loader entry，引擎的 `dsh-client-modules` 才会扫描本包的 `dsh.client` 声明
 * 并把浏览器端 bundle（`exports["./client"]`）注入页面。
 *
 * @module dsh-android-narrow-settings
 */

/** Cordis 插件名。 */
const name = "android-narrow-settings";

/** 宿主侧不依赖任何服务。 */
const inject = [];

/** 宿主端插件体：空实现（纯 UI 插件）。 */
function apply() {}

export { apply, inject, name };
