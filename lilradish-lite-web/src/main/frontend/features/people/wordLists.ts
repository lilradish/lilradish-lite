import { intl } from "../../i18n/intl";
import { isolatedInText } from "../../lib/direction/isolated";

// In the catalogue's language, not the reader's: the lists stand inside its
// sentences, where a joint in another language would stand out.
const WHOLE_SENTENCE = new Intl.ListFormat(intl.defaultLocale, {
  type: "conjunction",
});

const SHORT = new Intl.ListFormat(intl.defaultLocale, {
  type: "unit",
  style: "short",
});

/** Words read inside a sentence, each isolated from the others. */
export function joinedInSentence(words: readonly string[]): string {
  return WHOLE_SENTENCE.format(words.map(isolatedInText));
}

/** Words scanned in a table cell, each isolated from the others. */
export function joinedInCell(words: readonly string[]): string {
  return SHORT.format(words.map(isolatedInText));
}
