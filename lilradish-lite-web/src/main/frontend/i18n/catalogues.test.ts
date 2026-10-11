import { describe, expect, expectTypeOf, it } from "vitest";

import { joined } from "./catalogues";

describe("joined", () => {
  it("keeps complete topics and bare ids in one part with exact merged keys", () => {
    const catalogue = joined([
      {
        "parcel.title": "Parcel",
        "parcel.open": "Open parcel",
        receipt: "Receipt",
      },
      { "arrival.title": "Arrival", "read.custom": "Custom read" },
    ]);

    expect(catalogue).toEqual({
      "parcel.title": "Parcel",
      "parcel.open": "Open parcel",
      receipt: "Receipt",
      "arrival.title": "Arrival",
      "read.custom": "Custom read",
    });
    expectTypeOf(catalogue).toEqualTypeOf<{
      "parcel.title": string;
      "parcel.open": string;
      receipt: string;
      "arrival.title": string;
      "read.custom": string;
    }>();
  });

  it("refuses topics split between parts at compilation", () => {
    // @ts-expect-error A dotted topic belongs to one part.
    joined([{ "parcel.title": "Parcel" }, { "parcel.open": "Open parcel" }]);
    // @ts-expect-error A bare id also contributes its topic.
    joined([{ orphan: "Orphan" }, { "orphan.child": "Child" }]);
    // @ts-expect-error The order of the bare and dotted ids does not change ownership.
    joined([{ "orphan.child": "Child" }, { orphan: "Orphan" }]);
    // @ts-expect-error A repeated bare id belongs to the same topic.
    joined([{ orphan: "Orphan" }, { orphan: "Other orphan" }]);
    // @ts-expect-error An exact id owned by the library cannot be merged into the application.
    joined([{ "read.pending": "Still reading" }]);
  });
});
