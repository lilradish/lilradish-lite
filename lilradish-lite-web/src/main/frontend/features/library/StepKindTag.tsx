import AccountTreeIcon from "@mui/icons-material/AccountTree";
import AltRouteIcon from "@mui/icons-material/AltRoute";
import CodeIcon from "@mui/icons-material/Code";
import QuestionAnswerIcon from "@mui/icons-material/QuestionAnswer";
import SearchIcon from "@mui/icons-material/Search";
import Box from "@mui/material/Box";
import type SvgIcon from "@mui/material/SvgIcon";
import type { Theme } from "@mui/material/styles";

import type { RunsKind } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say, type MessageId } from "../../i18n/app";
import { MARKED_WORDS_SX } from "../../lib/layout/parts";
import { TOKENS } from "../../lib/theme/tokens";

const KINDS = {
  question: { words: "stepKind.question", Icon: QuestionAnswerIcon },
  workflow: { words: "stepKind.workflow", Icon: AccountTreeIcon },
  code_step: { words: "stepKind.code_step", Icon: CodeIcon },
  route: { words: "stepKind.route", Icon: AltRouteIcon },
  search: { words: "stepKind.search", Icon: SearchIcon },
} as const satisfies Record<
  RunsKind,
  { words: MessageId; Icon: typeof SvgIcon }
>;

const TAG_SX = {
  ...MARKED_WORDS_SX,
  typography: "kindBadge",
  px: 0.75,
  py: 0.25,
  borderRadius: TOKENS.shape.chipRadius,
};

export function isKnownKind(kind: string): kind is RunsKind {
  return Object.hasOwn(KINDS, kind);
}

export function stepKindTone(kind: RunsKind) {
  return kind === "code_step" ? "codeStep" : kind;
}

/** The kind a step runs, set apart in its own colour; a kind this build has no words for, said so plainly. */
export function StepKindTag({ kind }: { readonly kind: string }) {
  if (!isKnownKind(kind)) {
    return say("step.unknown");
  }
  const { words, Icon } = KINDS[kind];
  return (
    <Box
      component="span"
      sx={{
        ...TAG_SX,
        color: (given: Theme) => given.palette.kind[stepKindTone(kind)].text,
        bgcolor: (given: Theme) =>
          given.palette.kind[stepKindTone(kind)].ground,
      }}
    >
      <Icon fontSize="inherit" />
      {say(words)}
    </Box>
  );
}
