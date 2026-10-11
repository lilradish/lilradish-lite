import type { ReactNode } from "react";

import { SidePanelHost } from "./SidePanelHost";

/**
 * Where there is not room for both, the narrow column folds to a strip under the title and lies over the page when
 * opened. The wide one is drawn first, so the keyboard and a screen reader meet it first at any width.
 */
export function TwoColumns({
  wideAtLeast,
  wide,
  narrow,
  stickyNarrow = false,
  panelTitle,
}: {
  /** The width, in pixels, the wide column keeps before the narrow one stands beside it. */
  readonly wideAtLeast: number;
  readonly wide: ReactNode;
  readonly narrow: ReactNode;
  readonly stickyNarrow?: boolean;
  readonly panelTitle: string;
}) {
  return (
    <SidePanelHost
      open
      title={panelTitle}
      leastListWidth={wideAtLeast}
      list={wide}
      keptAs="lilradish.panel.about"
      sticky={stickyNarrow}
    >
      {narrow}
    </SidePanelHost>
  );
}
