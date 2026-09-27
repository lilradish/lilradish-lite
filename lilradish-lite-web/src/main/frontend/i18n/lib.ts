import type { LIB_EN } from "./en";
import { intl } from "./intl";

/**
 * Typed at the call site, so a renamed id fails the build rather than the eye.
 *
 * The narrow half: a control naming a business sentence would be a control that
 * only fits this application, and the compiler is what keeps that from being
 * discovered on the day it is reused.
 */
export function say(
  id: keyof typeof LIB_EN,
  values?: Record<string, string | number>,
): string {
  return intl.formatMessage({ id }, values);
}
