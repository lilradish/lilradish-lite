import { describe, expect, it } from "vitest";

import { nameOrNumber } from "./names";

describe("nameOrNumber", () => {
  it("shows somebody by the name held for them, and by their user number where none is", () => {
    expect(
      nameOrNumber({ userId: "000140", displayName: "Grace Hopper" }),
    ).toBe("Grace Hopper");
    expect(nameOrNumber({ userId: "000140" })).toBe("000140");
  });
});
