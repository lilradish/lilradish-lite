import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId, useMemo, useState, type ReactNode, type Ref } from "react";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import { say } from "../../i18n/app";
import { visible } from "../../i18n/visible";
import { Disclosure } from "../../lib/action/Disclosure";
import type { Ordering } from "../../lib/collection/ordering";
import { WholeTable } from "../../lib/collection/SortableTable";
import { isolatedInText } from "../../lib/direction/isolated";
import { DEPTH_SX, HEADING_SX, TITLE_LINE_SX } from "../../lib/layout/parts";
import { standingSaid } from "./libraryKinds";
import {
  NEWEST_FIRST,
  versionColumns,
  versionsInOrder,
  type VersionColumn,
} from "./versionColumns";

// Where the actions wrap onto a line of their own, they end it still.
const HEADING_LINE_SX = {
  ...TITLE_LINE_SX,
  ...HEADING_SX,
  justifyContent: "flex-end",
};

const HEADING_TEXT_SX = { flexGrow: 1 };

/**
 * The caller remounts this per entry, so a new entry gets its own default open state.
 */
export function Versions({
  versions,
  open,
  hrefOf,
  headingRef,
  actions,
  children,
}: {
  readonly versions: readonly Version[];
  readonly open: Version | undefined;
  readonly hrefOf: (version: Version) => string;
  /** A heading is held only to be focused, so this one takes focus from script. */
  readonly headingRef: Ref<HTMLHeadingElement>;
  /** Level with the heading, on the trailing side. */
  readonly actions: ReactNode;
  /** Said under the heading, above the table. */
  readonly children: ReactNode;
}) {
  const headingId = useId();
  const tableId = useId();
  const [shown, setShown] = useState(versions.length !== 1);
  const [order, setOrder] = useState<Ordering<VersionColumn>>(NEWEST_FIRST);
  const columns = useMemo(() => versionColumns(), []);

  return (
    <Box component="section" aria-labelledby={headingId} sx={DEPTH_SX[1]}>
      <Box sx={HEADING_LINE_SX}>
        <Typography
          id={headingId}
          ref={headingRef}
          tabIndex={-1}
          variant="h2"
          sx={HEADING_TEXT_SX}
        >
          <Disclosure
            open={shown}
            controls={tableId}
            onToggle={() => setShown((was) => !was)}
          >
            {say("versions.label")}
          </Disclosure>
        </Typography>
        {actions}
      </Box>
      {children}
      <div id={tableId}>
        {shown ? (
          <WholeTable
            label={say("versions.label")}
            columns={columns}
            order={order}
            onOrder={setOrder}
            rows={versionsInOrder(versions, order)}
            keyOf={versionIdOf}
            empty={say("versions.none")}
            picked={open?.versionId ?? null}
            hrefOf={hrefOf}
          />
        ) : null}
      </div>
      {shown || open === undefined ? null : (
        <Typography
          variant="body2"
          aria-label={say("versions.shut", {
            number: open.number,
            standing: isolatedInText(standingSaid(open.standing)),
          })}
        >
          {visible("versions.shut", {
            number: <span>{open.number}</span>,
            standing: (
              <Box
                component="span"
                sx={{ typography: "systemHint", color: "text.secondary" }}
              >
                <bdi>{standingSaid(open.standing)}</bdi>
              </Box>
            ),
          })}
        </Typography>
      )}
    </Box>
  );
}

function versionIdOf(version: Version): string {
  return version.versionId;
}
