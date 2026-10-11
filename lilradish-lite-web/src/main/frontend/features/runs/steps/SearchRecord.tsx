import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { createContext, useMemo } from "react";

import type { SearchesCounted, SearchSaid } from "../../../api/stepTries";
import { say } from "../../../i18n/app";
import { readersIntl } from "../../../i18n/intl";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX } from "../../../lib/layout/parts";
import { HiddenShown } from "../../../lib/text/HiddenShown";
import {
  ModelProse,
  FormattedProseAcross,
  ProseScope,
} from "../../../lib/text/ModelProse";
import { countSaid } from "../spend";
import { TOKENS } from "../../../lib/theme/tokens";

const SEARCH_CARD_SX = {
  bgcolor: "surface.card",
  border: "1px solid",
  borderColor: "surface.cardEdge",
  borderRadius: TOKENS.shape.cardRadius,
  p: "14px",
  mb: "10px",
  "& dl": { mb: 0 },
};

/** The try whose record is already drawn beside its review, so an open detail does not draw it twice. */
export const SearchReviewTry = createContext<number | null>(null);

export function SearchRecord({ said }: { readonly said: SearchSaid }) {
  const texts = useMemo(
    () =>
      "withheld" in said
        ? []
        : [
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
    <ProseScope>
      <FormattedProseAcross value={texts}>
        {(said.searches ?? []).map(({ searchedFor, found }, at) => (
          <Box key={at} sx={SEARCH_CARD_SX}>
            <Fields stacked>
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
          </Box>
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
              count: countSaid(said.searchesLeftOut),
              plural: said.searchesLeftOut === "1" ? 1 : 2,
            })}
          </Typography>
        )}
      </FormattedProseAcross>
    </ProseScope>
  );
}

function SearchText({ text }: { readonly text: string }) {
  const plain = (
    <Box component="bdi" sx={{ whiteSpace: "pre-wrap" }}>
      <HiddenShown text={text} />
    </Box>
  );
  return <ModelProse text={text} plain={plain} />;
}

export function SearchesCountedRows({
  counted,
}: {
  readonly counted: SearchesCounted;
}) {
  return (
    <>
      <SearchCount counted={counted} />
      <Field label={say("step.searchFee")}>
        <SearchFeeDrawn counted={counted} />
      </Field>
    </>
  );
}

export function SearchCount({
  counted,
}: {
  readonly counted: SearchesCounted;
}) {
  return (
    <Field label={say("step.searches")}>
      {say(
        counted.usesCounted === "said"
          ? "step.searchesSaid"
          : "step.searchesAtMost",
        {
          count: countSaid(counted.uses),
          plural: counted.uses === "1" ? 1 : 2,
        },
      )}
    </Field>
  );
}

export function SearchFeeDrawn({
  counted,
}: {
  readonly counted: SearchesCounted;
}) {
  return "fee" in counted ? (
    <>
      {moneySaid(counted.fee.currency, counted.fee.amount)}
      <Typography variant="body2" sx={QUIET_SX}>
        {say("step.searchPrice", {
          price: moneySaid(counted.fee.currency, counted.fee.perUse),
        })}
      </Typography>
    </>
  ) : counted.noFee.reason === "not_in_currency" ? (
    say("step.searchFeeNotInCurrency", { currency: counted.noFee.currency })
  ) : (
    say(
      counted.noFee.reason === "many_currencies"
        ? "step.searchFeeManyCurrencies"
        : "step.searchFeeNoPrice",
    )
  );
}

function moneySaid(currency: string, amount: string): string {
  return readersIntl.formatNumber(amount as `${number}`, {
    style: "currency",
    currency,
    maximumFractionDigits: 20,
  });
}
