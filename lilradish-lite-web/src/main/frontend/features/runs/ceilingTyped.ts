const TYPED = /^[1-9][0-9]{0,15}$/;

/** The largest the server takes, which is the largest whole number a number here holds exactly. */
const LARGEST = BigInt(Number.MAX_SAFE_INTEGER);

/** Whether what was typed is a ceiling as the server takes one: digits alone, leading with no zero. */
export function ceilingFits(typed: string): boolean {
  return TYPED.test(typed) && BigInt(typed) <= LARGEST;
}
