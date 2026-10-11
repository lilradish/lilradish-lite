import type { ComponentProps } from "react";
import { SidePanelHost } from "../layout/SidePanelHost";

export const LEAST_TABLE_WIDTH = 560;

/** Closing releases the pick; `listLabel` names the list focus returns to. */
type RowPanelProps = ComponentProps<typeof SidePanelHost> & {
  readonly onClose: () => void;
  readonly listLabel: string;
};

export function RowPanel(props: RowPanelProps) {
  return <SidePanelHost {...props} />;
}
