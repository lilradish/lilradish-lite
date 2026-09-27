import { createIntl, createIntlCache, type IntlShape } from "react-intl";

import { APP_EN, LIB_EN } from "./en";

// Memoizes the formatters, which are the expensive part of ICU.
const cache = createIntlCache();

/**
 * In the catalogue's language, not the reader's: a sentence's plural follows its
 * locale's rules, and under `ja` one of anything would take the English `other`.
 */
export const intl: IntlShape = createIntl(
  {
    locale: "en",
    defaultLocale: "en",
    messages: { ...LIB_EN, ...APP_EN },
  },
  cache,
);

/** The reader's own language, for dates, times, numbers and names only: it holds no sentence. */
export const readersIntl: IntlShape = createIntl(
  { locale: navigator.language, defaultLocale: "en" },
  cache,
);
