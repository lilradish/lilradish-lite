import { describe, expect, it, vi } from "vitest";

import { APP_EN, LIB_EN } from "./en";

/**
 * The module as a reader in another language gets it: the browser's language
 * is read once, when the module is first imported.
 */
async function forReaderIn(language: string) {
  vi.stubGlobal("navigator", { language });
  vi.resetModules();
  return import("./intl");
}

describe("intl", () => {
  /**
   * The two catalogues are spread into one, where an id in both keeps the
   * application's sentence and drops the library's without a word — a control
   * under `lib/` would then say whatever the business half wrote under its id.
   */
  it("holds two catalogues that share no id", () => {
    const shared = Object.keys(LIB_EN).filter((id) =>
      Object.hasOwn(APP_EN, id),
    );

    expect(shared).toEqual([]);
    expect(Object.keys(LIB_EN)).not.toEqual([]);
    expect(Object.keys(APP_EN)).not.toEqual([]);
  });

  /** Japanese has no plural form of its own, so under its rules one of anything is `other`. */
  it("picks an English sentence's plural form by English rules, whatever the reader's language", async () => {
    const { intl: underJapanese } = await forReaderIn("ja-JP");

    expect(
      underJapanese.formatMessage({ id: "version.pinCount" }, { count: 1 }),
    ).toBe("One version in service pins it.");
    expect(
      underJapanese.formatMessage({ id: "version.pinCount" }, { count: 2 }),
    ).toBe("2 versions in service pin it.");
  });
});

describe("readersIntl", () => {
  it("groups a number the reader's own way, not the catalogue's", async () => {
    const { intl: sentences, readersIntl } = await forReaderIn("de-DE");

    expect(readersIntl.formatNumber(1234567)).toBe("1.234.567");
    expect(sentences.formatNumber(1234567)).toBe("1,234,567");
  });
});
