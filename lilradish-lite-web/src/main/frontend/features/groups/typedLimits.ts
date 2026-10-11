// The page's half of a limit the server holds as well; the server's half is the one that holds.

const KEY_TYPED = /^[A-Za-z]{2,16}$/;

export function keyFits(typed: string): boolean {
  return KEY_TYPED.test(typed);
}
