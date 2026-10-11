import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import type { SxProps, Theme } from "@mui/material/styles";
import { useId, type ReactNode, type Ref } from "react";
import { Link as RouterLink } from "react-router";

import type {
  Version,
  VersionStanding as Standing,
} from "../../api/groups/{groupId}/{kind}";
import { say } from "../../i18n/app";
import { HEADING_SX, TITLE_LINE_SX } from "../../lib/layout/parts";
import { StatePill } from "../../lib/text/StatePill";
import type { StateToneName } from "../../lib/theme/tokens";
import { standingSaid } from "./libraryKinds";

// Where the actions wrap onto a line of their own, they end it still.
const HEADING_LINE_SX = {
  ...TITLE_LINE_SX,
  ...HEADING_SX,
  justifyContent: "flex-end",
};

const HEADING_TEXT_SX = { flexGrow: 1 };
const STANDING_TONES = {
  draft: "draft",
  submitted: "submitted",
  in_service: "inService",
  retired: "retired",
} as const satisfies Record<Standing, StateToneName>;
export function Versions({
  versions,
  open,
  hrefOf,
  headingRef,
  actions,
  sx,
  children,
}: {
  /** A card of its own on the page; a part of the side panel's card there. */
  readonly sx: SxProps<Theme>;
  readonly versions: readonly Version[];
  readonly open: Version | undefined;
  readonly hrefOf: (version: Version) => string;
  /** A heading is held only to be focused, so this one takes focus from script. */
  readonly headingRef: Ref<HTMLHeadingElement>;
  /** Level with the heading, on the trailing side. */
  readonly actions: ReactNode;
  /** Refusals stay beside the action that asked. */
  readonly children: ReactNode;
}) {
  const headingId = useId();

  return (
    <Box component="section" aria-labelledby={headingId} sx={sx}>
      <Box sx={HEADING_LINE_SX}>
        <Typography
          id={headingId}
          ref={headingRef}
          tabIndex={-1}
          variant="h2"
          sx={HEADING_TEXT_SX}
        >
          {say("versions.label")}
        </Typography>
        {actions}
      </Box>
      {children}
      {versions.length === 0 ? (
        <Typography>{say("versions.none")}</Typography>
      ) : (
        <Box
          component="ol"
          aria-labelledby={headingId}
          sx={{
            listStyle: "none",
            m: 0,
            p: 0,
            display: "flex",
            flexDirection: "column",
            gap: "10px",
            typography: "body2",
          }}
        >
          {[...versions]
            .sort((a, b) => b.number - a.number)
            .map((version) => (
              <Box component="li" key={version.versionId}>
                <Link
                  component={RouterLink}
                  to={hrefOf(version)}
                  aria-current={
                    open?.versionId === version.versionId ? "true" : undefined
                  }
                  sx={{
                    display: "grid",
                    gridTemplateColumns: "minmax(0, 1fr) auto",
                    alignItems: "center",
                    gap: "2px 12px",
                    p: "10px 12px",
                    borderRadius: "8px",
                    color: "text.primary",
                    bgcolor:
                      open?.versionId === version.versionId
                        ? "act.current"
                        : undefined,
                  }}
                >
                  <span>
                    {say("version.number", { number: version.number })}
                  </span>{" "}
                  <VersionStanding version={version} />
                  {version.versionName === null ? null : (
                    <Box
                      component="bdi"
                      dir="auto"
                      sx={{
                        gridColumn: "1 / -1",
                        typography: "supportingProse",
                        color: "text.secondary",
                      }}
                    >
                      {version.versionName}
                    </Box>
                  )}
                </Link>
              </Box>
            ))}
        </Box>
      )}
    </Box>
  );
}

export function VersionStanding({ version }: { readonly version: Version }) {
  const tone = Object.hasOwn(STANDING_TONES, version.standing)
    ? STANDING_TONES[version.standing as Standing]
    : undefined;
  return tone === undefined ? (
    <span>{standingSaid(version.standing)}</span>
  ) : (
    <StatePill tone={tone} label={standingSaid(version.standing)} />
  );
}
