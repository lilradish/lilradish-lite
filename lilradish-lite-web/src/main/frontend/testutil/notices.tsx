import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";

import { Notice, type Severity } from "../lib/notice/Notice";
import { theme } from "../lib/theme/theme";

const EVERY_SEVERITY = { info: 0, warning: 0, error: 0 } satisfies Record<
  Severity,
  0
>;

/** Every severity, and only those: the table above is closed over the type. */
export const SEVERITIES = Object.keys(EVERY_SEVERITY) as readonly Severity[];

/**
 * Every notice inside `container`, with the severity it is drawn in and the
 * words it holds. A notice is told by its role and its severity by the ground
 * each severity is drawn on — measured off a notice of each drawn here, never
 * off a class name. A notice on a ground none of them is drawn on is kept, with
 * no severity, rather than dropped.
 *
 * It sits in the source tree because the specs do: this project has no
 * separate test root to put it in. Only a spec may import it.
 */
export function noticesIn(
  container: HTMLElement,
): { readonly severity: Severity | null; readonly words: string }[] {
  const samples = render(
    <ThemeProvider theme={theme}>
      {SEVERITIES.map((severity) => (
        <Notice key={severity} severity={severity}>
          {severity}
        </Notice>
      ))}
    </ThemeProvider>,
  );
  const grounds = new Map(
    [...samples.container.querySelectorAll<HTMLElement>('[role="none"]')].map(
      (sample, at) => [
        getComputedStyle(sample).backgroundColor,
        SEVERITIES[at]!,
      ],
    ),
  );
  samples.unmount();
  if (grounds.size !== SEVERITIES.length) {
    throw new Error(
      `Two severities share a ground, so neither can be told by it: ${[...grounds.keys()].join(", ")}`,
    );
  }
  return [
    ...container.querySelectorAll<HTMLElement>('[role="none"], [role="alert"]'),
  ].map((each) => ({
    severity: grounds.get(getComputedStyle(each).backgroundColor) ?? null,
    words: each.textContent ?? "",
  }));
}
