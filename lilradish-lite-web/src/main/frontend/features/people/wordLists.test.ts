import { describe, expect, it, vi } from "vitest";

/** Words the lists set apart, bracketed by the isolate controls written as code points. */
function setApart(words: string): string {
  return `${String.fromCodePoint(0x2068)}${words}${String.fromCodePoint(0x2069)}`;
}

/**
 * The lists as a reader in another language gets them: the catalogue is read
 * once, when it is first imported, in whatever language the browser names.
 */
async function forReaderIn(language: string) {
  vi.stubGlobal("navigator", { ...navigator, language });
  vi.resetModules();
  return import("./wordLists");
}

describe("joinedInSentence", () => {
  /** The sentence around it is the catalogue's, so the joint has to be too. */
  it("joins words the way the catalogue's own language does, whatever the reader's is", async () => {
    const { joinedInSentence } = await forReaderIn("de-DE");

    expect(joinedInSentence(["Steward", "Watcher"])).toBe(
      `${setApart("Steward")} and ${setApart("Watcher")}`,
    );
  });

  it("sets each word apart, so no one of them can reorder the others", async () => {
    const { joinedInSentence } = await forReaderIn("en-GB");

    expect(joinedInSentence(["Payroll", "finance", "Audit"])).toBe(
      `${setApart("Payroll")}, ${setApart("finance")}, and ${setApart("Audit")}`,
    );
  });
});

describe("joinedInCell", () => {
  it("lists words with a comma alone, in the catalogue's language whatever the reader's is", async () => {
    const { joinedInCell } = await forReaderIn("de-DE");

    expect(joinedInCell(["Steward", "Watcher"])).toBe(
      `${setApart("Steward")}, ${setApart("Watcher")}`,
    );
  });
});
