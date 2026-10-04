import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { createContext, useMemo } from "react";

import type {
  SearchesCounted,
  SearchSaid,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say } from "../../../i18n/app";
import { readersIntl } from "../../../i18n/intl";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX } from "../../../lib/layout/parts";
import { HiddenShown, HiddenShownAcross } from "../../../lib/text/HiddenShown";

/** The try whose record is already drawn beside its review, so an open detail does not draw it twice. */
export const SearchReviewTry = createContext<number | null>(null);

export function SearchRecord({ said }: { readonly said: SearchSaid }) {
  const texts = useMemo(
    () =>
      "withheld" in said
        ? []
        : [
            said.question,
            ...(said.searches ?? []).flatMap(({ searchedFor, found }) =>
              [searchedFor, found].filter((text) => text !== undefined),
            ),
          ],
    [said],
  );
  if ("withheld" in said) {
    return (
      <Typography variant="body2">{say("step.searchWithheld")}</Typography>
    );
  }
  return (
    <HiddenShownAcross value={texts}>
      <Fields>
        <Field label={say("step.searchQuestion")}>
          <SearchText text={said.question} />
        </Field>
      </Fields>
      {(said.searches ?? []).map(({ searchedFor, found }, at) => (
        <Fields key={at}>
          {searchedFor === undefined ? null : (
            <Field label={say("step.searchedFor")}>
              <SearchText text={searchedFor} />
            </Field>
          )}
          {found === undefined ? null : (
            <Field label={say("step.searchFound")}>
              <SearchText text={found} />
            </Field>
          )}
        </Fields>
      ))}
      {said.searchesUnsaid === true ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.searchRecordUnsaid")}
        </Typography>
      ) : null}
      {said.searchesCut === true ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.searchRecordCut")}
        </Typography>
      ) : null}
      {said.searchesNotAsItCame === true ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.searchRecordNotAsItCame")}
        </Typography>
      ) : null}
      {said.searchesLeftOut === undefined ? null : (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.searchRecordMore", {
            count: Number(said.searchesLeftOut),
          })}
        </Typography>
      )}
    </HiddenShownAcross>
  );
}

export function SearchText({ text }: { readonly text: string }) {
  return (
    <Box component="bdi" sx={{ whiteSpace: "pre-wrap" }}>
      <HiddenShown text={text} />
    </Box>
  );
}

export function SearchesCountedRows({
  counted,
}: {
  readonly counted: SearchesCounted;
}) {
  return (
    <>
      <Field label={say("step.searches")}>
        {say(
          counted.usesCounted === "said"
            ? "step.searchesSaid"
            : "step.searchesAtMost",
          { count: Number(counted.uses) },
        )}
      </Field>
      {"fee" in counted ? (
        <>
          <Field label={say("step.searchPrice")}>
            {moneySaid(counted.fee.currency, counted.fee.perUse)}
          </Field>
          <Field label={say("step.searchFee")}>
            {moneySaid(counted.fee.currency, counted.fee.amount)}
          </Field>
        </>
      ) : (
        <Field label={say("step.searchFee")}>
          {counted.noFee.reason === "not_in_currency"
            ? say("step.searchFeeNotInCurrency", {
                currency: counted.noFee.currency,
              })
            : say(
                counted.noFee.reason === "many_currencies"
                  ? "step.searchFeeManyCurrencies"
                  : "step.searchFeeNoPrice",
              )}
        </Field>
      )}
    </>
  );
}

function moneySaid(currency: string, amount: string): string {
  return readersIntl.formatNumber(amount as `${number}`, {
    style: "currency",
    currency,
    maximumFractionDigits: 20,
  });
}
