import { describe, expect, it } from "vitest";

import { personFrom } from "./person";

describe("personFrom", () => {
  it.each([
    ["with a name", { userId: "000c01", displayName: "Ada" }],
    ["with none", { userId: "000c01" }],
  ])("reads somebody %s, adding nothing in its place", (_case, body) => {
    expect(personFrom(body)).toEqual(body);
  });

  it.each([
    ["no object at all", "000c01"],
    ["no user number", { displayName: "Ada" }],
    ["a user number that is no text", { userId: 7 }],
    ["a name that is no text", { userId: "000c01", displayName: 7 }],
    ["a name sent as null", { userId: "000c01", displayName: null }],
  ])("refuses %s rather than showing part of somebody", (_case, body) => {
    expect(personFrom(body)).toBeNull();
  });
});
