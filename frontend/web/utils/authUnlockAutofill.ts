/** 解锁只读伪装，便于浏览器自动填充（登录 / 找回密码共用） */
export function unlockAuthInputAutofill(e: React.SyntheticEvent) {
  const target = e.target
  if (target instanceof HTMLInputElement) {
    target.removeAttribute('readonly')
  }
}
