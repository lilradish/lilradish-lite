import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";

import {
  chooseCurrency,
  readCurrency,
  type CurrencyChoice,
} from "../../api/groups/{groupId}/currency";
import type { Problem } from "../../api/problem";
import { Async } from "../../app/Async";
import { ProblemView } from "../../app/ProblemView";
import { movesTheReader } from "../../app/refusal";
import type { Rule } from "../../app/standing/actRules";
import { say, type WordedRefusal } from "../../i18n/app";
import { readersIntl } from "../../i18n/intl";
import { InlineEdit } from "../../lib/action/InlineEdit";
import { InlineEditableValue } from "../../lib/action/InlineEditableValue";
import { useAction } from "../../lib/request/useAction";
import { useResource } from "../../lib/request/useResource";

const NOTHING_READ: CurrencyChoice = { offered: [] };

const NOT_PRICED: WordedRefusal = "CURRENCY_NOT_PRICED";

const READS: Rule = { inGroup: "read_membership" };

const CHANGES: Rule = { inGroup: "change_membership" };

const PICKER_SX = { display: "flex", flexDirection: "column", gap: 1, mb: 2 };

const SUMMARY_SX = {
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: 1,
};

const SELECT_SX = { width: "100%", minWidth: 0 };

const QUIET_SX = { color: "text.secondary" };

// Out of the list and out of reach, yet an item all the same: Material warns
// of a value no item holds.
const KEPT_SX = { display: "none" };

/** A currency chosen, and the read it was chosen over, which it stands in for until that read is replaced. */
interface Answered {
  readonly over: CurrencyChoice;
  readonly choice: CurrencyChoice;
}

type PickerProps = {
  readonly groupId: string;
  readonly mayChange: boolean;
  readonly onChosen: (said: string) => void;
  readonly onMoved: () => void;
};

export function CurrencyPicker(props: PickerProps) {
  return <CurrencyChoicePicker key={props.groupId} {...props} />;
}

function CurrencyChoicePicker({
  groupId,
  mayChange,
  onChosen,
  onMoved,
}: PickerProps) {
  const load = useCallback(
    (signal: AbortSignal) => readCurrency(groupId, signal),
    [groupId],
  );
  const read = useResource(load, NOTHING_READ);
  const reread = read.reload;
  const [answered, setAnswered] = useState<Answered | null>(null);
  const [showChoices, setShowChoices] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [drafted, setDrafted] = useState<string | null>(null);
  const [previousMayChange, setPreviousMayChange] = useState(mayChange);
  const readControl = useRef<HTMLButtonElement>(null);
  const returnFocus = useRef(false);
  useLayoutEffect(() => {
    if (!showChoices) {
      if (returnFocus.current && mayChange) readControl.current?.focus();
      returnFocus.current = false;
    }
  }, [showChoices, mayChange]);
  if (previousMayChange !== mayChange) {
    setPreviousMayChange(mayChange);
    if (!mayChange) {
      setShowChoices(false);
      setMenuOpen(false);
      setDrafted(null);
    }
  }
  const choosing = useAction<Answered>(
    (answer) => {
      setAnswered(answer);
      setShowChoices(false);
      setDrafted(null);
      if (answer.choice.chosen !== undefined) {
        onChosen(currencySaid(answer.choice.chosen));
      }
    },
    (problem) => {
      if (movesTheReader(problem)) {
        onMoved();
      }
      // What is offered moved since it was read, and only a read says how.
      if (refusedAsNotPriced(problem)) {
        reread();
      }
    },
  );

  return (
    <Async read={read} rule={READS} empty={() => null}>
      {(value) => {
        const shown = answered?.over === value ? answered.choice : value;
        const { chosen, offered } = shown;
        const notPriced = chosen !== undefined && !offered.includes(chosen);
        const selected = drafted ?? chosen ?? "";
        const selectedIsUnpriced =
          selected !== "" && !offered.includes(selected);
        return (
          <Box sx={PICKER_SX}>
            <Box sx={{ ...SUMMARY_SX, width: "100%", minHeight: 32 }}>
              {!showChoices && mayChange && offered.length > 0 ? (
                <InlineEditableValue
                  ref={readControl}
                  value={
                    chosen === undefined
                      ? say("currency.noneChosen")
                      : currencySaid(chosen)
                  }
                  disabled={choosing.running}
                  onEdit={() => {
                    setDrafted(chosen ?? "");
                    setShowChoices(true);
                  }}
                />
              ) : showChoices ? null : (
                <Typography variant="body2" sx={QUIET_SX}>
                  {say("currency.read", {
                    currency:
                      chosen === undefined
                        ? say("currency.noneChosen")
                        : currencySaid(chosen),
                  })}
                </Typography>
              )}
              {notPriced && !showChoices ? (
                <Typography variant="body2" sx={QUIET_SX}>
                  {say("currency.notPriced")}
                </Typography>
              ) : null}
              {mayChange && showChoices ? (
                <InlineEdit
                  readValue={
                    chosen === undefined
                      ? say("currency.noneChosen")
                      : currencySaid(chosen)
                  }
                  open
                  onShut={(restoring = false) => {
                    returnFocus.current = restoring;
                    setShowChoices(false);
                    setDrafted(null);
                  }}
                  action={choosing}
                  unchanged={selected === chosen || selected === ""}
                  choiceOpen={menuOpen}
                  confirmAfterChange={drafted ?? undefined}
                  act={(signal) =>
                    chooseCurrency(groupId, selected, signal).then(
                      (choice) => ({
                        over: value,
                        choice,
                      }),
                    )
                  }
                  reason={
                    selected === "" ||
                    selected === chosen ||
                    !offered.includes(selected)
                      ? {
                          severity: "info",
                          words: say("currency.read", {
                            currency:
                              chosen === undefined
                                ? say("currency.noneChosen")
                                : currencySaid(chosen),
                          }),
                        }
                      : undefined
                  }
                  refusal={null}
                >
                  <TextField
                    select
                    fullWidth
                    size="small"
                    value={selected}
                    onChange={(picked) => {
                      setDrafted(picked.target.value);
                    }}
                    helperText={
                      selectedIsUnpriced ? say("currency.notPriced") : undefined
                    }
                    slotProps={{
                      input: { readOnly: choosing.running },
                      select: {
                        "aria-label": say("currency.label"),
                        autoFocus: true,
                        onOpen: () => setMenuOpen(true),
                        onClose: () => setMenuOpen(false),
                        SelectDisplayProps: {
                          onKeyDownCapture: closedTypeaheadStopped,
                        },
                        displayEmpty: true,
                        renderValue: (code) =>
                          code === ""
                            ? say("currency.noneChosen")
                            : currencySaid(code as string),
                      },
                    }}
                    sx={SELECT_SX}
                  >
                    {selectedIsUnpriced ? (
                      <MenuItem value={selected} disabled sx={KEPT_SX}>
                        {currencySaid(selected)}
                      </MenuItem>
                    ) : null}
                    {offered.map((code) => (
                      <MenuItem key={code} value={code}>
                        {currencySaid(code)}
                      </MenuItem>
                    ))}
                  </TextField>
                </InlineEdit>
              ) : null}
            </Box>
            {choosing.problem === null ? null : (
              <ProblemView problem={choosing.problem} rule={CHANGES} />
            )}
          </Box>
        );
      }}
    </Async>
  );
}

/** Its code, and its name in the reader's language where that language has one. */
function currencySaid(code: string): string {
  const name = readersIntl.formatDisplayName(code, {
    type: "currency",
    fallback: "none",
  });
  return name === undefined ? code : say("currency.named", { code, name });
}

// Material's closed select picks an item for a letter; consuming that key leaves the draft unchanged.
function closedTypeaheadStopped(pressed: KeyboardEvent<HTMLDivElement>) {
  if (
    pressed.key.length === 1 &&
    pressed.key !== " " &&
    !pressed.ctrlKey &&
    !pressed.metaKey &&
    !pressed.altKey
  ) {
    pressed.preventDefault();
  }
}

function refusedAsNotPriced(problem: Problem): boolean {
  return problem.status !== undefined && problem.code === NOT_PRICED;
}
