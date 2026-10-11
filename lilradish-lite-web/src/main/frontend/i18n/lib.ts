import type { LIB_EN } from "./en";
import { intl } from "./intl";

/**
 * The compiler rejects renamed ids and business sentences in reusable controls;
 * otherwise a control could quietly depend on this application's catalogue.
 */
export function say(
  id: keyof typeof LIB_EN,
  values?: Record<string, string | number>,
): string {
  return intl.formatMessage({ id }, values);
}
