import Box from "@mui/material/Box";
import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useCallback, useState, type KeyboardEvent } from "react";

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
import { useAction } from "../../lib/request/useAction";
import { useResource } from "../../lib/request/useResource";

const NOTHING_READ: CurrencyChoice = {};

const NOT_PRICED: WordedRefusal = "CURRENCY_NOT_PRICED";

const READS: Rule = { inGroup: "read_membership" };

const CHANGES: Rule = { inGroup: "change_membership" };

const PICKER_SX = { display: "flex", flexDirection: "column", gap: 1 };

const SELECT_SX = { minWidth: 220 };

const QUIET_SX = { color: "text.secondary" };

// Out of the list and out of reach, yet an item all the same: Material warns
// of a value no item holds.
const KEPT_SX = { display: "none" };

/** A currency chosen, and the read it was chosen over, which it stands in for until that read is replaced. */
interface Answered {
  readonly over: CurrencyChoice;
  readonly choice: CurrencyChoice;
}

/**
 * A choice is saved as it is made and cannot be taken back, so only the opened list chooses. A currency no
 * model is priced in now stays shown as the choice, and is not offered.
 */
export function CurrencyPicker({
  groupId,
  onChosen,
  onMoved,
}: {
  readonly groupId: string;
  /** Told the currency chosen, as it is said. */
  readonly onChosen: (said: string) => void;
  /** Told the reader's standing has moved under the page. */
  readonly onMoved: () => void;
}) {
  const load = useCallback(
    (signal: AbortSignal) => readCurrency(groupId, signal),
    [groupId],
  );
  const read = useResource(load, NOTHING_READ);
  const reread = read.reload;
  const [answered, setAnswered] = useState<Answered | null>(null);
  const choosing = useAction<Answered>(
    (answer) => {
      setAnswered(answer);
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
        const notPriced =
          offered !== undefined &&
          chosen !== undefined &&
          !offered.includes(chosen);
        return (
          <Box sx={PICKER_SX}>
            {offered === undefined || offered.length === 0 ? (
              <>
                <Typography>
                  {say("currency.read", {
                    currency:
                      chosen === undefined
                        ? say("currency.noneChosen")
                        : currencySaid(chosen),
                  })}
                </Typography>
                {notPriced ? (
                  <Typography variant="body2" sx={QUIET_SX}>
                    {say("currency.notPriced")}
                  </Typography>
                ) : null}
              </>
            ) : (
              <TextField
                select
                size="small"
                label={say("currency.label")}
                value={chosen ?? ""}
                onChange={(picked) => {
                  const code = picked.target.value;
                  choosing.run((signal) =>
                    chooseCurrency(groupId, code, signal).then((choice) => ({
                      over: value,
                      choice,
                    })),
                  );
                }}
                helperText={notPriced ? say("currency.notPriced") : undefined}
                slotProps={{
                  input: { readOnly: choosing.running },
                  select: {
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
                {notPriced ? (
                  <MenuItem value={chosen} disabled sx={KEPT_SX}>
                    {currencySaid(chosen)}
                  </MenuItem>
                ) : null}
                {offered.map((code) => (
                  <MenuItem key={code} value={code}>
                    {currencySaid(code)}
                  </MenuItem>
                ))}
              </TextField>
            )}
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

// Material's closed select picks the item a letter starts, which here saves it; its typeahead yields to a
// key already handled. Space still opens the list.
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
