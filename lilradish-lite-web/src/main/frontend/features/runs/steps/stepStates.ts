import type { StepState } from "../../../api/stepStates";
import type { Theme } from "@mui/material/styles";

import { say, type MessageId } from "../../../i18n/app";
import type { StateToneName } from "../../../lib/theme/tokens";

const TONES = {
  not_started: "notStarted",
  running: "running",
  held_back: "blocked",
  waiting: "waiting",
  failed: "failed",
  done: "done",
} as const satisfies Record<StepState, StateToneName>;

export function stepStateTone(state: StepState): StateToneName;
export function stepStateTone(state: string): StateToneName | undefined;
export function stepStateTone(state: string): StateToneName | undefined {
  return Object.hasOwn(TONES, state) ? TONES[state as StepState] : undefined;
}

export const STATES = {
  not_started: "step.notStarted",
  running: "step.running",
  held_back: "step.heldBack",
  waiting: "step.waiting",
  failed: "step.failed",
  done: "step.done",
} as const satisfies Record<StepState, MessageId>;

/** Words this build has for a spelling, or a plain saying that it has none; never the server's spelling. */
export function worded<T extends Record<string, MessageId>>(
  words: T,
  spelt: string,
): string {
  return wordedIfKnown(words, spelt) ?? say("step.unknown");
}

export function wordedIfKnown<T extends Record<string, MessageId>>(
  words: T,
  spelt: string,
): string | null {
  return Object.hasOwn(words, spelt) ? say(words[spelt as keyof T]!) : null;
}

export function stepStateSaid(state: string): string {
  return worded(STATES, state);
}

/** The words for a state this build has words for, and null for any other. */
export function stepStateWorded(state: string): string | null {
  return wordedIfKnown(STATES, state);
}

export function stepStateColour(state: StepState, theme: Theme): string {
  return theme.palette.state[TONES[state]].text;
}
