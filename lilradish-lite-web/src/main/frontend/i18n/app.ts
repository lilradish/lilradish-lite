import type { APP_EN, LIB_EN } from "./en";
import { intl } from "./intl";

/** Every id this side can name, which is both catalogues and nothing else. */
export type MessageId = keyof typeof LIB_EN | keyof typeof APP_EN;

type Served<Id> = Id extends `refusal.${infer Code}` ? Code : never;

/** A served code this side has a sentence for, so a code misspelt against it fails to compile. */
export type WordedRefusal = Served<keyof typeof APP_EN>;

/** Typed at the call site, so a renamed id fails the build rather than the eye. */
export function say(
  id: MessageId,
  values?: Record<string, string | number>,
): string {
  return intl.formatMessage({ id }, values);
}
