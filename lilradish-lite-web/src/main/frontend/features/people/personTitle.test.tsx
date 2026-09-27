import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { personTitle } from "./personTitle";

describe("personTitle", () => {
  it("says somebody by user number and then name, each set apart", () => {
    const { container } = render(
      <p>{personTitle({ userId: "000130", displayName: "Ada Lovelace" })}</p>,
    );

    expect(
      [...container.querySelectorAll("bdi")].map((each) => each.textContent),
    ).toEqual(["000130", "Ada Lovelace"]);
    expect(container.textContent).toBe("000130 Ada Lovelace");
  });

  it("says somebody no name is held for by user number alone, inventing nothing", () => {
    const { container } = render(<p>{personTitle({ userId: "000150" })}</p>);

    expect(container.textContent).toBe("000150");
    expect(container.querySelectorAll("bdi")).toHaveLength(1);
  });
});
