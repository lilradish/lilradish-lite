import { render } from "@testing-library/react";
import { RouterProvider, createMemoryRouter } from "react-router";
import { describe, it } from "vitest";

import { namingGroupsByKeyAlone } from "./groupAddresses";

const PAYROLL_ID = "00000003-0000-4000-8000-000000000f01";

/** A page holding one link, watched for links naming Payroll by its identifier. */
function drawing(href: string) {
  const router = createMemoryRouter([
    { path: "/", element: <a href={href}>Work</a> },
  ]);
  namingGroupsByKeyAlone(router, [PAYROLL_ID]);
  render(<RouterProvider router={router} />);
}

/** The watcher fails a spec once it is over, so only a spec expected to fail can show it does. */
describe("namingGroupsByKeyAlone", () => {
  it.fails(
    "fails a spec that drew a link naming a group by its identifier",
    () => {
      drawing(`/groups/${PAYROLL_ID}/work`);
    },
  );

  it("passes a spec whose links name the group by its key", () => {
    drawing("/groups/PAYROLL/work");
  });
});
