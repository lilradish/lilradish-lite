import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import { useContext, useId, useState, type ReactNode } from "react";

import type { Declared } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import type { GroupStanding } from "../../../api/standing";
import { isDownloadableFileValue, type ReadField } from "../../../api/filling";
import type {
  Cost,
  Declined,
  DeclinedText,
  UnreadableResponse,
  KeptCodeFile,
  Try,
  TryDetails,
  WentWrong,
} from "../../../api/stepTries";
import { say } from "../../../i18n/app";
import { Press } from "../../../lib/action/Press";
import { Disclosure } from "../../../lib/action/Disclosure";
import { TABLE_SX, CELL_SX } from "../../../lib/collection/table";
import { readersIntl } from "../../../i18n/intl";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX, RULED_PART_SX } from "../../../lib/layout/parts";
import { PANEL_PART_SX } from "../../../lib/layout/SidePanelHost";
import { HiddenShown } from "../../../lib/text/HiddenShown";
import { HiddenShownIn } from "../../../lib/text/HiddenShownIn";
import { ModelProse, ProseScope } from "../../../lib/text/ModelProse";
import { TOKENS } from "../../../lib/theme/tokens";
import { nameOrNumber } from "../../../lib/text/names";
import { FileCard } from "../fill/FileCard";
import { FilledValue } from "../fill/FilledValue";
import { CopyFailure, CopyValue } from "../fill/ValueCard";
import { CostDrawn } from "../spend";
import { DecisionDrawn, LengthDecisionDrawn } from "./DecisionDrawn";
import { fieldAt, labelAt, type Declaring } from "./declared";
import { whoSaid } from "./stepProducersAndReviewers";
import {
  tryResultSaid,
  tryResultVisible,
  erroredForVisible,
  misfitSaid,
  reviewVisible,
  standingSaid,
  tryEndedSaid,
  tryEndedVisible,
} from "./stepTries";
import { Turnaways } from "./Turnaways";
import {
  SearchCount,
  SearchFeeDrawn,
  SearchRecord,
  SearchReviewTry,
} from "./SearchRecord";
import { TryMaterial } from "./TryRead";

type HeadingLevel = "h3" | "h4";

const WORDS_SX = { whiteSpace: "pre-wrap" };

const USAGE_SX = { fontVariantNumeric: "tabular-nums" };

const PART_TITLE_SX = {
  typography: "sideTitle",
  color: "ink.strong",
  m: "0 0 10px",
};

// Fields and a reply's group each wrap what they hold in a box of their own, so
// a part's outer margins can sit one level further in.
const PART_EDGES_SX = {
  "& > section > :last-child, & > section > :last-child > :last-child": {
    mb: 0,
  },
  "& > section > :not(style):not(:not(style) ~ *), & > section > :not(style):not(:not(style) ~ *) > :not(style):not(:not(style) ~ *)":
    {
      mt: 0,
    },
};

// A rule reaches the card's edges only while the parts sit straight in the
// card's padding: anything between that adds an inset of its own cuts it short.
export const PARTS_SX = {
  message: {
    minWidth: 0,
    ...PART_EDGES_SX,
    "& > section + section": RULED_PART_SX,
  },
  panel: {
    minWidth: 0,
    ...PART_EDGES_SX,
    "& > section + section": PANEL_PART_SX,
  },
};

interface TryPanelProps {
  readonly aTry: Try;
  readonly field: string | undefined;
  readonly declared: Declared | undefined;
  readonly declaring: Declaring;
  readonly material: TryDetails | undefined;
  readonly group: GroupStanding;
}

export function TryPanel({
  aTry,
  field,
  declared,
  declaring,
  group,
}: {
  readonly aTry: Try;
  readonly field: string | undefined;
  readonly declared: Declared | undefined;
  readonly declaring: Declaring;
  readonly group: GroupStanding;
}) {
  return (
    <TryMaterial number={aTry.number}>
      {(material) => (
        <TryPanelDrawn
          aTry={aTry}
          field={field}
          declared={declared}
          declaring={declaring}
          material={material}
          group={group}
        />
      )}
    </TryMaterial>
  );
}

function TryPanelDrawn(props: TryPanelProps) {
  return (
    <ProseScope>
      <TryParts {...props} within="panel" />
    </ProseScope>
  );
}

export function Part({
  title,
  level,
  children,
}: {
  readonly title: string;
  readonly level: HeadingLevel;
  readonly children: ReactNode;
}) {
  return (
    <section>
      <Typography variant={level} sx={PART_TITLE_SX}>
        {title}
      </Typography>
      {children}
    </section>
  );
}

export function UsageDrawn({ cost }: { readonly cost: Cost }) {
  return (
    <Box sx={USAGE_SX}>
      <CostDrawn cost={cost} />
    </Box>
  );
}

export function TryParts({
  within,
  usage,
  inputs,
  ...props
}: TryPanelProps & {
  readonly within: keyof typeof PARTS_SX;
  readonly usage?: Cost;
  readonly inputs?: ReactNode;
}) {
  const reviewingSearch = useContext(SearchReviewTry);
  const level = within === "message" ? "h3" : "h4";
  return (
    <Box sx={PARTS_SX[within]}>
      <section>
        <TrySummary {...props} usage={usage} />
      </section>
      {props.material?.search === undefined ||
      reviewingSearch === props.aTry.number ? null : (
        <Part title={say("step.whatItFound")} level={level}>
          <SearchRecord said={props.material.search.said} />
        </Part>
      )}
      <Part title={say("step.reviewHeading")} level={level}>
        <TryReview {...props}>
          <TryOutcome {...props} />
        </TryReview>
      </Part>
      {inputs}
      <TryReplies {...props} level={level} />
    </Box>
  );
}

function TrySummary({
  aTry,
  field,
  declared,
  material,
  group,
  usage,
}: TryPanelProps & { readonly usage: Cost | undefined }) {
  const gives = declared?.gives;
  const value =
    field === undefined
      ? undefined
      : aTry.values.find((each) => each.field === field);
  const counted = aTry.search?.counted;
  return (
    <Fields properties>
      <Field label={say("step.askedBy")}>
        {aTry.askedBy === undefined
          ? say("step.askedByRun")
          : isolatedInText(nameOrNumber(aTry.askedBy))}
      </Field>
      <Field label={say("step.producedBy")}>
        {whoSaid(aTry.producedBy)}
        {aTry.values.map((each) =>
          each.confidence === undefined ||
          (field !== undefined && each.field !== field) ? null : (
            <Typography key={each.field} variant="body2" sx={QUIET_SX}>
              {say("step.sureOf", {
                field: labelAt(gives, each.field),
                percent: percentSaid(each.confidence),
              })}
            </Typography>
          ),
        )}
        {aTry.didNotFit !== "model_declined" ? null : (
          <>
            <Typography variant="body2" sx={QUIET_SX}>
              {misfitSaid(aTry.didNotFit)}
            </Typography>
            <DeclinedWords declined={material?.declined} />
          </>
        )}
      </Field>
      {aTry.why === undefined ? null : (
        <Field label={say("step.why")} across>
          <Box component="bdi" sx={WORDS_SX}>
            {aTry.why}
          </Box>
        </Field>
      )}
      {field === undefined ? null : (
        <Field label={labelAt(gives, field)} across>
          {value === undefined ? (
            say("step.gaveNothingFor")
          ) : (
            <>
              <FilledValue
                cards
                field={fieldAt(gives, field)}
                label={labelAt(gives, field)}
                shown={value}
                shut={false}
                status={value.now}
                foot={
                  value.now === "none" ||
                  (value.decision?.outcome === "refused" &&
                    (value.decision.why !== undefined ||
                      value.decision.withheld === true)) ||
                  value.decision?.outcome === "refused_for_length" ? (
                    <>
                      {value.now === "none" ? (
                        <Typography variant="body2">
                          {standingSaid(value.now)}
                        </Typography>
                      ) : null}
                      {value.decision?.outcome === "refused" &&
                      (value.decision.why !== undefined ||
                        value.decision.withheld === true) ? (
                        <DecisionDrawn decision={value.decision} />
                      ) : null}
                      {value.decision?.outcome === "refused_for_length" ? (
                        <LengthDecisionDrawn
                          decision={value.decision}
                          group={group}
                          field={labelAt(gives, field)}
                        />
                      ) : null}
                    </>
                  ) : undefined
                }
              />
            </>
          )}
        </Field>
      )}
      {usage === undefined ? null : (
        <Field label={say("run.usage")}>
          <UsageDrawn cost={usage} />
        </Field>
      )}
      {counted === undefined ? null : (
        <>
          <SearchCount counted={counted} />
          <Field label={say("step.searchFee")} across>
            <SearchFeeDrawn counted={counted} />
          </Field>
        </>
      )}
    </Fields>
  );
}

function TryReview({
  aTry,
  field,
  declared,
  material,
  children,
}: TryPanelProps & { readonly children?: ReactNode }) {
  const gives = declared?.gives;
  const values = aTry.values.filter(
    (each) => field === undefined || each.field === field,
  );
  return (
    <>
      <Fields properties>
        <Field label={say("step.reviewedBy")}>
          {reviewVisible(aTry.review)}
          {aTry.review.didNotFit !== "model_declined" ? null : (
            <>
              <Typography variant="body2" sx={QUIET_SX}>
                {misfitSaid(aTry.review.didNotFit)}
              </Typography>
              <DeclinedWords declined={material?.review?.declined} />
            </>
          )}
        </Field>
      </Fields>
      {children}
      {values.length === 0 ? null : (
        <Table aria-label={say("step.decision")} sx={TABLE_SX}>
          <TableHead>
            <TableRow>
              <TableCell>{say("step.reviewValue")}</TableCell>
              <TableCell>{say("step.reviewDecision")}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody sx={CELL_SX}>
            {values.map((value) => (
              <TableRow key={value.field}>
                <TableCell component="th" scope="row">
                  {labelAt(gives, value.field)}
                </TableCell>
                <TableCell>
                  {value.decision === undefined ? (
                    standingSaid(value.now)
                  ) : value.now === "refused_for_length" ? (
                    standingSaid(value.now)
                  ) : (
                    <DecisionDrawn decision={value.decision} />
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </>
  );
}

function TryOutcome({
  aTry,
  field,
  declared,
  declaring,
  material,
  group,
}: TryPanelProps) {
  const gives = declared?.gives;
  const resultSaid = tryResultSaid(aTry, field);
  const endedSaid = tryEndedSaid(aTry);
  return (
    <Fields>
      <Field label={say("step.howItEnded")}>
        {tryResultVisible(aTry, field)}
        {aTry.values
          .filter(
            (each) =>
              each.now === "refused_for_length" &&
              (field === undefined || field === each.field),
          )
          .map((each) =>
            each.decision === undefined ? null : (
              <LengthDecisionDrawn
                key={each.field}
                decision={each.decision}
                group={group}
                field={labelAt(gives, each.field)}
              />
            ),
          )}
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
      {(aTry.wentWrong ?? material?.wentWrong) === undefined ? null : (
        <Field label={say("step.wentWrong")}>
          <WentWrongDrawn
            wentWrong={(aTry.wentWrong ?? material?.wentWrong)!}
          />
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
            <KeptFile file={file} field={fieldAt(gives, fieldPath)} />
          </Field>
        );
      })}
      {material?.returned === undefined &&
      aTry.erroredFor?.returnedNotKept !== true ? null : (
        <Field label={say("step.returned")}>
          {aTry.erroredFor?.returnedNotKept === true ? (
            say("step.erroredForNotKept")
          ) : (
            <Returned key={aTry.number} text={material!.returned!} />
          )}
        </Field>
      )}
    </Fields>
  );
}

function TryReplies({
  aTry,
  material,
  level,
}: TryPanelProps & { readonly level: HeadingLevel }) {
  const producerAnswer =
    material?.declined !== undefined && !("withheld" in material.declined)
      ? material.declined.content
      : undefined;
  const reviewAnswer =
    material?.review?.declined !== undefined &&
    !("withheld" in material.review.declined)
      ? material.review.declined.content
      : undefined;
  const hasProducer =
    producerAnswer !== undefined ||
    material?.unreadableResponse !== undefined ||
    material?.modelReply !== undefined;
  const hasReview =
    reviewAnswer !== undefined ||
    material?.review?.unreadableResponse !== undefined ||
    material?.review?.modelReply !== undefined;
  if (material === undefined || (!hasProducer && !hasReview)) return null;
  return (
    <Box component="section" key={aTry.number}>
      {hasProducer ? (
        <Box role="group" aria-label={say("step.producedBy")}>
          <DeclinedAnswer
            declined={material?.declined}
            replyDrawn={material?.modelReply !== undefined}
            level={level}
          />
          <UnreadableResponseDrawn
            response={material?.unreadableResponse}
            level={level}
          />
          <ModelReplyDrawn reply={material?.modelReply} level={level} />
        </Box>
      ) : null}
      {hasReview ? (
        <Box role="group" aria-label={say("step.reviewedBy")}>
          <DeclinedAnswer
            declined={material?.review?.declined}
            replyDrawn={material?.review?.modelReply !== undefined}
            level={level}
          />
          <UnreadableResponseDrawn
            response={material?.review?.unreadableResponse}
            level={level}
          />
          <ModelReplyDrawn reply={material?.review?.modelReply} level={level} />
        </Box>
      ) : null}
    </Box>
  );
}

/** Kept model words and answer, in the order the model supplied its two parts. */
export function DeclinedDrawn({
  declined,
  replyDrawn = false,
}: {
  readonly declined: Declined | undefined;
  readonly replyDrawn?: boolean;
}) {
  if (declined === undefined || "withheld" in declined) {
    return null;
  }
  return (
    <Box>
      <DeclinedWords declined={declined} />
      <DeclinedAnswer declined={declined} replyDrawn={replyDrawn} />
    </Box>
  );
}

function DeclinedWords({
  declined,
}: {
  readonly declined: Declined | undefined;
}) {
  return declined === undefined ||
    "withheld" in declined ||
    declined.words === undefined ? null : (
    <DeclinedPart
      key={`words-${declined.words.text}`}
      label={say("step.declinedWords")}
      part={declined.words}
      formatted={declined.words.lines === "several"}
    />
  );
}

function DeclinedAnswer({
  declined,
  replyDrawn,
  level,
}: {
  readonly declined: Declined | undefined;
  readonly replyDrawn: boolean;
  readonly level?: HeadingLevel;
}) {
  return declined === undefined ||
    "withheld" in declined ||
    declined.content === undefined ||
    replyDrawn ? null : (
    <DeclinedPart
      key={`content-${declined.content.text}`}
      label={say("step.modelReply")}
      part={declined.content}
      level={level}
    />
  );
}

export function ModelReplyDrawn({
  reply,
  level,
}: {
  readonly reply: DeclinedText | undefined;
  readonly level?: HeadingLevel;
}) {
  return reply === undefined ? null : (
    <DeclinedPart
      key={reply.text}
      label={say("step.modelReply")}
      part={reply}
      level={level}
    />
  );
}

export function UnreadableResponseDrawn({
  response,
  level,
}: {
  readonly response: UnreadableResponse | undefined;
  readonly level?: HeadingLevel;
}) {
  return response === undefined ? null : (
    <DeclinedPart
      key={response.text}
      label={say("step.unreadableResponse")}
      part={response}
      stopped={response.stopped}
      level={level}
    />
  );
}

function DeclinedPart({
  label,
  part,
  formatted = false,
  stopped = false,
  level,
}: {
  readonly label: string;
  readonly part: DeclinedText;
  readonly formatted?: boolean;
  readonly stopped?: boolean;
  readonly level?: HeadingLevel;
}) {
  const [open, setOpen] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const textId = useId();
  const labelId = useId();
  const notes = (
    <>
      {part.altered ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.declinedAltered")}
        </Typography>
      ) : null}
      {stopped ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.unreadableResponseStopped")}
        </Typography>
      ) : part.truncated ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("step.declinedTruncated")}
        </Typography>
      ) : null}
    </>
  );
  if (!formatted) {
    const opening = (
      <Disclosure
        open={open}
        controls={textId}
        onToggle={() => setOpen((was) => !was)}
      >
        <Box component="span" id={labelId} sx={{ typography: "resultTitle" }}>
          {label}
        </Box>
      </Disclosure>
    );
    return (
      <Box
        sx={{
          border: "1px solid",
          borderColor: "surface.rule",
          borderRadius: TOKENS.shape.cardRadius,
          p: "14px",
          my: "14px",
        }}
      >
        <Box
          sx={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 1,
          }}
        >
          {/* A heading around a disclosure holds that control alone: Copy stays outside it. */}
          {level === undefined ? (
            opening
          ) : (
            <Typography component={level} variant="inherit" sx={{ m: 0 }}>
              {opening}
            </Typography>
          )}
          <CopyValue
            key={part.text}
            text={part.text}
            describedBy={labelId}
            onCopyFailed={setCopyFailed}
          />
        </Box>
        <CopyFailure failed={copyFailed} />
        {notes}
        <Box id={textId} hidden={!open}>
          {open ? (
            <Box
              component="bdi"
              sx={{
                ...WORDS_SX,
                typography: "rawReply",
                display: "block",
                overflowWrap: "anywhere",
                maxHeight: "12rem",
                overflow: "auto",
              }}
            >
              <HiddenShown text={part.text} />
            </Box>
          ) : null}
        </Box>
      </Box>
    );
  }
  return (
    <Fields stacked>
      <Field label={label}>
        <ModelProse
          text={part.text}
          plain={
            <Box component="bdi" sx={WORDS_SX}>
              <HiddenShown text={part.text} />
            </Box>
          }
        />
        {notes}
      </Field>
    </Fields>
  );
}

function KeptFile({
  file,
  field,
}: {
  readonly file: KeptCodeFile;
  readonly field: ReadField | undefined;
}) {
  const metadata = { id: file.id, name: file.name, size: file.size };
  if (!isDownloadableFileValue(metadata)) {
    return say("step.unknown");
  }
  return <FileCard file={metadata} field={field} />;
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

function percentSaid(confidence: number): string {
  return readersIntl.formatNumber(confidence / 100, { style: "percent" });
}

/** Shut until opened, however short: it is code's own output as it came back, which may run to millions of characters. */
function Returned({ text }: { readonly text: string }) {
  const [open, setOpen] = useState(false);
  const textId = useId();
  return (
    <>
      <Press
        tier="quiet"
        aria-expanded={open}
        aria-controls={textId}
        onPress={() => setOpen((was) => !was)}
      >
        {say("step.inFull", { field: say("step.returned") })}
      </Press>
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
