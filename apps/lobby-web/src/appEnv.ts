/**
 * 原生 APP（WebView 壳）环境识别与本地偏好。
 * 网页端保持原有行为；仅在 APP 内启用「记住账号」「隐藏第三方 OAuth」等移动端特性。
 */

const REMEMBER_KEY = 'cc.remembered.account';

/** 是否运行在棋牌 APP 的 WebView 内（UA 由 MainActivity 注入 CcGameApp 标记） */
export function isNativeApp(): boolean {
  return /CcGameApp/i.test(navigator.userAgent);
}

/** 读取已记住的账号；网页端始终返回空 */
export function getRememberedAccount(): string {
  if (!isNativeApp()) return '';
  try {
    return localStorage.getItem(REMEMBER_KEY) ?? '';
  } catch {
    return '';
  }
}

/** 记住账号（仅 APP 内）。传空串表示清除。 */
export function rememberAccount(account: string): void {
  if (!isNativeApp()) return;
  try {
    if (account) localStorage.setItem(REMEMBER_KEY, account);
    else localStorage.removeItem(REMEMBER_KEY);
  } catch {
    /* ignore */
  }
}
