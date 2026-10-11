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
      underJapanese.formatMessage(
        { id: "version.pinnedByCount" },
        { count: 1 },
      ),
    ).toBe("1 version:");
    expect(
      underJapanese.formatMessage(
        { id: "version.pinnedByCount" },
        { count: 2 },
      ),
    ).toBe("2 versions:");
  });
});

describe("readersIntl", () => {
  it("formats every digit of a plain decimal string in the reader's locale", async () => {
    const { decimalSaid } = await forReaderIn("de-DE");
    expect(decimalSaid("900719925474099312345.125678")).toBe(
      "900.719.925.474.099.312.345,125678",
    );
    expect(decimalSaid("1234567.8900")).toBe("1.234.567,8900");
    expect(decimalSaid("-0.0000000000000000000000000000000000001")).toBe(
      "-0,0000000000000000000000000000000000001",
    );
  });
  it("formats plain and plural message numbers for the reader while retaining English grammar", async () => {
    const { intl } = await forReaderIn("de-DE");
    expect(
      intl.formatMessage(
        { id: "declaration.longestSaid" },
        { longest: 262144 },
      ),
    ).toBe("At most 262.144 characters");
    expect(
      intl.formatMessage({ id: "version.pinnedByCount" }, { count: 2000 }),
    ).toBe("2.000 versions:");
    expect(
      intl.formatMessage({ id: "version.pinnedByCount" }, { count: 1 }),
    ).toBe("1 version:");
  });

  it("keeps arbitrary bigint digits exact in numeric messages without routing them through a plural operand", async () => {
    const { intl, readersIntl } = await forReaderIn("de-DE");
    const value = 900719925474099312345n;
    expect(
      intl.formatMessage({ id: "step.orderOf" }, { order: value, of: value }),
    ).toBe("900.719.925.474.099.312.345 of 900.719.925.474.099.312.345");
    expect(readersIntl.formatNumber(value)).toBe("900.719.925.474.099.312.345");
  });
  it("groups a number the reader's own way, not the catalogue's", async () => {
    const { intl: sentences, readersIntl } = await forReaderIn("de-DE");

    expect(readersIntl.formatNumber(1234567)).toBe("1.234.567");
    expect(sentences.formatNumber(1234567)).toBe("1,234,567");
  });
});
