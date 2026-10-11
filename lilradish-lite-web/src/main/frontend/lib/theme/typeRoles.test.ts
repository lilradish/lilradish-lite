import { describe, expect, it } from "vitest";
import { typeRoleValueInRem, typeRolesInRem } from "./typeRoles";

describe("the typography consumption units", () => {
  it.each([
    ["14px", "0.875rem"],
    ["22px", "1.375rem"],
    ["13.5px", "0.84375rem"],
    ["inherit", "inherit"],
    ["1.5", "1.5"],
  ])("converts %s to %s", (value, expected) => {
    expect(typeRoleValueInRem("fontSize", value)).toBe(expected);
  });

  it.each([
    ["fontSize", "0.75rem"],
    ["lineHeight", "0.75rem"],
    ["letterSpacing", "12px"],
    ["fontWeight", "12px"],
  ])(
    "follows the reader's text size for size and leading only: %s becomes %s",
    (property, expected) => {
      expect(typeRoleValueInRem(property, "12px")).toBe(expected);
    },
  );

  it("converts size and leading together without rewriting the design source", () => {
    const roles = {
      body: { fontSize: "14px", lineHeight: "22px", fontWeight: 400 },
      label: { fontSize: "12px", lineHeight: "inherit", fontWeight: 600 },
    } as const;
    expect(typeRolesInRem(roles)).toEqual({
      body: { fontSize: "0.875rem", lineHeight: "1.375rem", fontWeight: 400 },
      label: { fontSize: "0.75rem", lineHeight: "inherit", fontWeight: 600 },
    });
    expect(roles.body.fontSize).toBe("14px");
    expect(roles.body.lineHeight).toBe("22px");
  });
});
