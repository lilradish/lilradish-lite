import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { useContext, useId, useState } from "react";

import type { Declared } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { isDownloadableFileValue, type ReadField } from "../../../api/filling";
import type {
  Declined,
  DeclinedText,
  KeptCodeFile,
  TriedValue,
  Try,
  WentWrong,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say } from "../../../i18n/app";
import { readersIntl } from "../../../i18n/intl";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX } from "../../../lib/layout/parts";
import { HiddenShown } from "../../../lib/text/HiddenShown";
import { HiddenShownIn } from "../../../lib/text/HiddenShownIn";
import { nameOrNumber } from "../../../lib/text/names";
import {
  CurrentFileRunContext,
  fileDownloadHref,
} from "../fill/CurrentFileRun";
import { FilledValue } from "../fill/FilledValue";
import { DecisionDrawn } from "./DecisionDrawn";
import { fieldAt, labelAt, type Declaring } from "./declared";
import {
  tryResultSaid,
  tryResultVisible,
  erroredForVisible,
  misfitSaid,
  reviewVisible,
  standingSaid,
  tryEndedSaid,
  tryEndedVisible,
  whoSaid,
} from "./stepStates";
import { Turnaways } from "./Turnaways";
import {
  SearchRecord,
  SearchesCountedRows,
  SearchReviewTry,
} from "./SearchRecord";

const WORDS_SX = { whiteSpace: "pre-wrap" };

const SURE_SX = { m: 0, pl: 2 };

export function TryPanel({
  aTry,
  field,
  declared,
  declaring,
}: {
  readonly aTry: Try;
  readonly field: string | undefined;
  readonly declared: Declared | undefined;
  readonly declaring: Declaring;
}) {
  const reviewingSearch = useContext(SearchReviewTry);
  const gives = declared?.gives;
  const value =
    field === undefined
      ? undefined
      : aTry.values.find((each) => each.field === field);
  const resultSaid = tryResultSaid(aTry, field);
  const endedSaid = tryEndedSaid(aTry);
  return (
    <>
      {aTry.search === undefined || reviewingSearch === aTry.number ? null : (
        <SearchRecord said={aTry.search.said} />
      )}
      <Fields>
        <Field label={say("step.askedBy")}>
          {aTry.askedBy === undefined
            ? say("step.askedByRun")
            : isolatedInText(nameOrNumber(aTry.askedBy))}
        </Field>
        <Field label={say("step.producedBy")}>
          {whoSaid(aTry.producedBy)}
          {aTry.didNotFit !== "model_declined" ? null : (
            <>
              <Typography variant="body2" sx={QUIET_SX}>
                {misfitSaid(aTry.didNotFit)}
              </Typography>
              <DeclinedDrawn declined={aTry.declined} />
            </>
          )}
          <UnreadableResponseDrawn response={aTry.unreadableResponse} />
        </Field>
        {aTry.search === undefined ? null : (
          <>
            {aTry.search.counted === undefined ? null : (
              <SearchesCountedRows counted={aTry.search.counted} />
            )}
          </>
        )}
        {aTry.why === undefined ? null : (
          <Field label={say("step.why")}>
            <Box component="bdi" sx={WORDS_SX}>
              {aTry.why}
            </Box>
          </Field>
        )}
        {field === undefined ? null : (
          <Field label={labelAt(gives, field)}>
            {value === undefined ? (
              say("step.gaveNothingFor")
            ) : (
              <>
                <FilledValue
                  field={fieldAt(gives, field)}
                  label={labelAt(gives, field)}
                  shown={value}
                  shut={false}
                />
                <Typography variant="body2" sx={QUIET_SX}>
                  {standingSaid(value.now)}
                </Typography>
              </>
            )}
          </Field>
        )}
        {field === undefined ? (
          <>
            <SureOfEach values={aTry.values} gives={gives} />
            <DecisionsOfEach values={aTry.values} gives={gives} />
          </>
        ) : value?.confidence === undefined ? null : (
          <Field label={say("step.howSure")}>
            {percentSaid(value.confidence)}
          </Field>
        )}
        <Field label={say("step.reviewedBy")}>
          {reviewVisible(aTry.review)}
          {aTry.review.didNotFit !== "model_declined" ? null : (
            <>
              <Typography variant="body2" sx={QUIET_SX}>
                {misfitSaid(aTry.review.didNotFit)}
              </Typography>
              <DeclinedDrawn declined={aTry.review.declined} />
            </>
          )}
          <UnreadableResponseDrawn response={aTry.review.unreadableResponse} />
        </Field>
        {value?.decision === undefined ||
        value.now === "refused_for_length" ? null : (
          <Field label={say("step.decision")}>
            <DecisionDrawn decision={value.decision} />
          </Field>
        )}
        <Field label={say("step.howItEnded")}>
          {tryResultVisible(aTry, field)}
          {field === undefined || resultSaid === endedSaid ? null : (
            <Typography variant="body2" sx={QUIET_SX}>
              {tryEndedVisible(aTry)}
            </Typography>
          )}
          {(aTry.review.didNotFit ?? aTry.didNotFit) === undefined ||
          (aTry.review.didNotFit ?? aTry.didNotFit) ===
            "model_declined" ? null : (
            <Typography variant="body2" sx={QUIET_SX}>
              {misfitSaid((aTry.review.didNotFit ?? aTry.didNotFit)!)}
            </Typography>
          )}
        </Field>
        {aTry.turnedAway === undefined ? null : (
          <Field label={say("step.turnedAway")}>
            <Turnaways turnedAway={aTry.turnedAway} />
          </Field>
        )}
        {aTry.wentWrong === undefined ? null : (
          <Field label={say("step.wentWrong")}>
            <WentWrongDrawn wentWrong={aTry.wentWrong} />
          </Field>
        )}
        {aTry.erroredFor === undefined ? null : (
          <Field label={say("step.wentWrong")}>
            {erroredForVisible(aTry.erroredFor, declared, declaring)}
          </Field>
        )}
        {aTry.keptFiles?.map((file) => {
          const fieldPath = file.path.replace(/\[\d+\]/g, "");
          const label = labelAt(gives, fieldPath);
          return (
            <Field key={file.path} label={label}>
              <KeptFile file={file} />
            </Field>
          );
        })}
        {aTry.returned === undefined &&
        aTry.erroredFor?.returnedNotKept !== true ? null : (
          <Field label={say("step.returned")}>
            {aTry.erroredFor?.returnedNotKept === true ? (
              say("step.erroredForNotKept")
            ) : (
              <Returned key={aTry.number} text={aTry.returned!} />
            )}
          </Field>
        )}
      </Fields>
    </>
  );
}

/** Kept model text is shown literally, in the order the model supplied its two parts. */
export function DeclinedDrawn({
  declined,
}: {
  readonly declined: Declined | undefined;
}) {
  if (declined === undefined || "withheld" in declined) {
    return null;
  }
  return (
    <Fields stacked>
      {declined.words === undefined ? null : (
        <DeclinedPart label={say("step.declinedWords")} part={declined.words} />
      )}
      {declined.content === undefined ? null : (
        <DeclinedPart
          label={say("step.declinedContent")}
          part={declined.content}
        />
      )}
    </Fields>
  );
}

export function UnreadableResponseDrawn({
  response,
}: {
  readonly response: DeclinedText | undefined;
}) {
  return response === undefined ? null : (
    <Fields stacked>
      <DeclinedPart label={say("step.unreadableResponse")} part={response} />
    </Fields>
  );
}

function DeclinedPart({
  label,
  part,
}: {
  readonly label: string;
  readonly part: DeclinedText;
}) {
  return (
    <Field label={label}>
      <Box component="bdi" sx={WORDS_SX}>
        <HiddenShown text={part.text} />
      </Box>
      {part.altered ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.declinedAltered")}
        </Typography>
      ) : null}
      {part.truncated ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.declinedTruncated")}
        </Typography>
      ) : null}
    </Field>
  );
}

function KeptFile({ file }: { readonly file: KeptCodeFile }) {
  const run = useContext(CurrentFileRunContext);
  if (
    !isDownloadableFileValue({
      id: file.id,
      name: file.name,
      type: file.type,
      size: file.size,
    })
  ) {
    return say("step.unknown");
  }
  const name = (
    <Box component="bdi">
      <HiddenShown text={file.name} />
    </Box>
  );
  return (
    <Box component="span">
      {run === null ? (
        name
      ) : (
        <Link href={fileDownloadHref(run, file.id)} download>
          {say("step.downloadFile")} {name}
        </Link>
      )}{" "}
      {say("step.fileType", { type: isolatedInText(file.type) })}{" "}
      {say("step.fileSize", { size: file.size })}
    </Box>
  );
}

function WentWrongDrawn({ wentWrong }: { readonly wentWrong: WentWrong }) {
  if ("withheld" in wentWrong) {
    return say("step.withheld");
  }
  return wentWrong.cut ? (
    <HiddenShownIn
      hint
      sentence={(detail) => say("step.wentWrongCut", { detail })}
      text={wentWrong.detail}
    />
  ) : (
    <bdi>
      <HiddenShown text={wentWrong.detail} />
    </bdi>
  );
}

function SureOfEach({
  values,
  gives,
}: {
  readonly values: readonly TriedValue[];
  readonly gives: readonly ReadField[] | undefined;
}) {
  const sure = values.flatMap(({ field, confidence }) =>
    confidence === undefined ? [] : [{ field, confidence }],
  );
  return sure.length === 0 ? null : (
    <Field label={say("step.howSure")}>
      <Box component="ul" sx={SURE_SX}>
        {sure.map(({ field, confidence }) => (
          <li key={field}>
            {say("step.sureOf", {
              field: labelAt(gives, field),
              percent: percentSaid(confidence),
            })}
          </li>
        ))}
      </Box>
    </Field>
  );
}

function DecisionsOfEach({
  values,
  gives,
}: {
  readonly values: readonly TriedValue[];
  readonly gives: readonly ReadField[] | undefined;
}) {
  const decided = values.filter(
    (value) =>
      value.decision !== undefined && value.now !== "refused_for_length",
  );
  return decided.length === 0 ? null : (
    <Field label={say("step.decision")}>
      <Box component="ul" sx={SURE_SX}>
        {decided.map((value) => (
          <li key={value.field}>
            {labelAt(gives, value.field)}:{" "}
            <DecisionDrawn decision={value.decision!} />
          </li>
        ))}
      </Box>
    </Field>
  );
}

function percentSaid(confidence: number): string {
  return readersIntl.formatNumber(confidence / 100, { style: "percent" });
}

/** Shut until opened, however short: it is code's own output as it came back, which may run to millions of characters. */
function Returned({ text }: { readonly text: string }) {
  const [open, setOpen] = useState(false);
  const textId = useId();
  return (
    <>
      <Button
        variant="outlined"
        color="inherit"
        size="small"
        aria-expanded={open}
        aria-controls={textId}
        onClick={() => setOpen((was) => !was)}
      >
        {say("step.inFull", { field: say("step.returned") })}
      </Button>
      <div id={textId} hidden={!open}>
        {open ? (
          <Box component="bdi" sx={WORDS_SX}>
            <HiddenShown text={text} />
          </Box>
        ) : null}
      </div>
    </>
  );
}
