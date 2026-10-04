// It sits in the source tree because the specs do; only a spec may import it.

import Box from "@mui/material/Box";
import { ThemeProvider } from "@mui/material/styles";
import { render } from "@testing-library/react";

import { DEPTH_SX } from "../lib/layout/parts";
import { theme } from "../lib/theme/theme";

/** What tells one depth from another, read off a box as drawn. */
export function drawnOf(box: Element) {
  const drawn = getComputedStyle(box);
  return {
    topEdge: drawn.borderTopWidth,
    leadingEdge: `${drawn.borderLeftWidth} ${drawn.borderLeftStyle}`,
    leadingEdgeColour: drawn.borderLeftColor,
    ground: drawn.backgroundColor,
    // In pixels: this environment reports a padding nothing set as a bare 0.
    padding: [drawn.paddingTop, drawn.paddingLeft]
      .map((side) => `${parseFloat(side)}px`)
      .join(" "),
  };
}

/** The same off a box drawn at `depth` and nothing else, so a spec names the depth and never its values. */
export function drawnAtDepth(depth: keyof typeof DEPTH_SX) {
  const probe = render(
    <ThemeProvider theme={theme}>
      <Box sx={DEPTH_SX[depth]} />
    </ThemeProvider>,
  );
  const drawn = drawnOf(probe.container.firstElementChild!);
  probe.unmount();
  probe.container.remove();
  return drawn;
}
