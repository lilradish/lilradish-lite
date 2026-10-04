import type { ReactNode } from "react";

import { say } from "../../i18n/app";
import { Fields } from "../../lib/form/Fields";
import { Part } from "../../lib/layout/Part";

/**
 * What is about a run, or one of its steps, under its own heading: a region named by it rather than a complementary
 * landmark, as it holds what may be done to the run and sits within the page's main content. Its facts are stacked,
 * the narrow column it stands in leaving no room for a label beside its value.
 */
export function AboutIt({
  facts,
  children,
  status,
}: {
  /** Each a `Field`. */
  readonly facts: ReactNode;
  /** What may be done, under the facts. */
  readonly children?: ReactNode;
  readonly status?: ReactNode;
}) {
  return (
    <Part title={say("run.aboutIt")} level="h2" depth={1} actions={status}>
      <Fields stacked>{facts}</Fields>
      {children}
    </Part>
  );
}
