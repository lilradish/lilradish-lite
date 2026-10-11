import type { StepState } from "../../../api/stepStates";
import AutorenewIcon from "@mui/icons-material/Autorenew";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ErrorOutlinedIcon from "@mui/icons-material/ErrorOutlined";
import HourglassEmptyIcon from "@mui/icons-material/HourglassEmpty";
import PauseCircleOutlinedIcon from "@mui/icons-material/PauseCircleOutlined";
import RadioButtonUncheckedIcon from "@mui/icons-material/RadioButtonUnchecked";
import type SvgIcon from "@mui/material/SvgIcon";

import { say } from "../../../i18n/app";
import { StatePill } from "../../../lib/text/StatePill";
import { stepStateTone, stepStateWorded } from "./stepStates";

const STATE_ICONS = {
  not_started: RadioButtonUncheckedIcon,
  running: AutorenewIcon,
  held_back: PauseCircleOutlinedIcon,
  waiting: HourglassEmptyIcon,
  failed: ErrorOutlinedIcon,
  done: CheckCircleIcon,
} as const satisfies Record<StepState, typeof SvgIcon>;

function isKnownState(state: string): state is StepState {
  return Object.hasOwn(STATE_ICONS, state);
}

export function StepStateBadge({ state }: { readonly state: string }) {
  if (!isKnownState(state)) {
    return say("step.unknown");
  }
  const Icon = STATE_ICONS[state];
  return (
    <StatePill
      tone={stepStateTone(state)}
      label={stepStateWorded(state)}
      icon={<Icon fontSize="inherit" />}
    />
  );
}
