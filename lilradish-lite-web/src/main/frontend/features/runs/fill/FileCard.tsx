import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import IconButton from "@mui/material/IconButton";
import CloseIcon from "@mui/icons-material/Close";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import InsertDriveFileOutlinedIcon from "@mui/icons-material/InsertDriveFileOutlined";
import { useContext, useId } from "react";

import type { FileValue, ReadField } from "../../../api/filling";
import { say } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { cardPadding } from "../../../lib/layout/parts";
import { HiddenShown, HiddenShownAcross } from "../../../lib/text/HiddenShown";
import { HiddenShownIn } from "../../../lib/text/HiddenShownIn";
import { sizeSaid } from "../../../lib/text/sizeSaid";
import { TOKENS } from "../../../lib/theme/tokens";
import { CurrentFileRunContext, fileDownloadHref } from "./CurrentFileRun";

const CARD_SX = {
  display: "block",
  width: 240,
  maxWidth: "100%",
  ...cardPadding(TOKENS.space.valueCard),
  borderWidth: 1,
  borderStyle: "solid",
  borderColor: TOKENS.surface.cardEdge,
  borderRadius: TOKENS.shape.cardRadius,
  overflowWrap: "anywhere",
  bgcolor: "background.paper",
};

export function FileCard({
  file,
  field,
  preparedEnding,
  withinValueCard = false,
  onRemove,
}: {
  readonly file: FileValue | Pick<FileValue, "name" | "size">;
  readonly field?: ReadField;
  readonly preparedEnding?: ReturnType<typeof fileEndingOf>;
  readonly withinValueCard?: boolean;
  readonly onRemove?: () => void;
}) {
  const run = useContext(CurrentFileRunContext);
  const labelId = useId();
  const removeLabelId = useId();
  const ending = preparedEnding ?? fileEndingOf(file.name, field);
  const size = sizeSaid(file.size);
  const frame = withinValueCard
    ? { ...CARD_SX, borderWidth: 0, p: 0 }
    : CARD_SX;
  const contents = (
    <>
      <Box
        component="bdi"
        sx={{ display: "flex", alignItems: "center", gap: 1, minWidth: 0 }}
      >
        <InsertDriveFileOutlinedIcon
          sx={{ width: 20, height: 20 }}
          aria-hidden
        />
        <Box component="span" sx={{ display: "flex", minWidth: 0, flex: 1 }}>
          <Box
            component="span"
            sx={{
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
              minWidth: 0,
            }}
          >
            <HiddenShown
              text={file.name}
              ending={
                ending.ending === undefined
                  ? undefined
                  : file.name.slice(-ending.ending.length)
              }
              part="before-ending"
            />
          </Box>
          {ending.ending === undefined ? null : (
            <Box component="span" sx={{ flexShrink: 0, whiteSpace: "nowrap" }}>
              <HiddenShown
                text={file.name}
                ending={file.name.slice(-ending.ending.length)}
                part="ending"
              />
            </Box>
          )}
        </Box>
      </Box>{" "}
      <Typography
        component="small"
        variant="body2"
        sx={{
          typography: "systemHint",
          display: "block",
          color: "text.secondary",
        }}
      >
        {size}
      </Typography>
    </>
  );
  return (
    <HiddenShownAcross value={{ name: file.name }}>
      <Box
        component="span"
        sx={{
          ...frame,
          display: "inline-flex",
          alignItems: "flex-start",
          gap: 1,
        }}
      >
        {run === null || !("id" in file) ? (
          <Tooltip title={<HiddenShown text={file.name} />} describeChild>
            <Box component="span" tabIndex={0} sx={{ minWidth: 0, flex: 1 }}>
              {contents}
            </Box>
          </Tooltip>
        ) : (
          <Tooltip title={<HiddenShown text={file.name} />} describeChild>
            <Link
              href={fileDownloadHref(run, file.id)}
              download
              aria-labelledby={labelId}
              underline="hover"
              sx={{ display: "block", minWidth: 0, flex: 1 }}
            >
              <span id={labelId} hidden data-copy-control>
                <HiddenShownIn
                  text={file.name}
                  sentence={(name) =>
                    say("fill.fileDownload", {
                      name: isolatedInText(name),
                      size,
                    })
                  }
                />
              </span>
              {contents}
            </Link>
          </Tooltip>
        )}
        {onRemove === undefined ? null : (
          <Tooltip title={say("fill.takeAway")} describeChild>
            <IconButton
              size="small"
              data-copy-control
              aria-labelledby={removeLabelId}
              onClick={onRemove}
            >
              <span hidden id={removeLabelId}>
                <HiddenShownIn
                  text={file.name}
                  sentence={(name) =>
                    say("fill.removeFile", { name: isolatedInText(name) })
                  }
                />
              </span>
              <CloseIcon sx={{ width: 20, height: 20 }} />
            </IconButton>
          </Tooltip>
        )}
      </Box>
    </HiddenShownAcross>
  );
}

export function fileEndingOf(
  name: string,
  field?: ReadField,
): {
  readonly ending: string | undefined;
} {
  const folded = asciiLower(name);
  let ending: string | undefined;
  if (field?.kind === "file") {
    for (const declared of field.types ?? []) {
      if (
        folded.endsWith(asciiLower(declared)) &&
        declared.length > (ending?.length ?? 0)
      ) {
        ending = declared;
      }
    }
  }
  if (ending === undefined) {
    const dot = name.lastIndexOf(".");
    ending = dot === -1 ? undefined : name.slice(dot);
  }
  return {
    ending,
  };
}

function asciiLower(text: string): string {
  return text.replace(/[A-Z]/g, (letter) => letter.toLowerCase());
}
