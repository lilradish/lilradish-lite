import { describe, expect, it } from "vitest";

import { sizeSaid } from "./sizeSaid";

describe("file size wording", () => {
  it.each([
    [0, "0 B"],
    [1_023, "1,023 B"],
    [1_024, "1 KB"],
    [1_536, "1.5 KB"],
    [1_048_576, "1 MB"],
    [1_073_741_824, "1 GB"],
    [2_147_483_648, "2 GB"],
  ])("says %s bytes as %s", (bytes, words) => {
    expect(sizeSaid(bytes)).toBe(words);
  });
});
