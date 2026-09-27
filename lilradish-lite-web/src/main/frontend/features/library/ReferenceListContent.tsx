import { Fragment, useCallback } from "react";

import type { Version } from "../../api/groups/{groupId}/{kind}";
import {
  readReferenceList,
  type ReferenceListVersion,
} from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { writeNote } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/note";
import { addTerm } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms";
import { rewordTerm } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms/{termId}";
import { removeTerm } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms/{termId}/removal";
import { moveTerm } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}/terms/{termId}/{way}";
import type { Problem } from "../../api/problem";
import { Async } from "../../app/Async";
import type { MessageId } from "../../i18n/app";
import { useAction } from "../../lib/request/useAction";
import { noteRefused, type ProseRefusal } from "../../lib/text/legibility";
import { VERSION_ACT_RULES } from "./actRules";
import { ProsePart, type ProseWords } from "./ProsePart";
import { TermsTable } from "./TermsTable";
import { useVersionContent } from "./useVersionContent";

/** Each reason the server refuses a note, said as the refusal it would answer with. */
const NOTE_REFUSALS = {
  crlf: "refusal.PROSE_LINE_BREAK_CRLF",
  direction_control: "refusal.PROSE_DIRECTION_CONTROL",
  tag: "refusal.PROSE_TAG_CHARACTER",
  unusable: "refusal.NOTE_UNUSABLE",
} as const satisfies Record<ProseRefusal, MessageId>;

const NOTE_WORDS: ProseWords = {
  title: "referenceList.note",
  hint: "referenceList.noteHint",
  saysNothing: "referenceList.saysNothing",
  save: "referenceList.saveNote",
  unchanged: "referenceList.noteUnchanged",
  underway: "referenceList.underway",
};

/** Which part a press to read the version afresh came from, which is where the keyboard goes back to. */
type Part = "note" | "terms";

/** Each change is written on its own over the revision last shown; while one is out, or a read, nothing else is sent. */
export function ReferenceListContent({
  groupId,
  entryId,
  version,
  readCount,
  onRefused,
  onWritten,
  onShown,
}: {
  readonly groupId: string;
  readonly entryId: string;
  readonly version: Version;
  /** Changed each time the page reads the entry again, which is when this reads the list again too. */
  readonly readCount: number;
  readonly onRefused: (problem: Problem) => void;
  /** Somebody wrote the list, here or elsewhere, so whatever the page holds of the version before it is stale. */
  readonly onWritten: () => void;
  readonly onShown: (list: ReferenceListVersion | null) => void;
}) {
  const load = useCallback(
    (signal: AbortSignal): Promise<ReferenceListVersion | null> =>
      readReferenceList(groupId, entryId, version.versionId, signal),
    [groupId, entryId, version.versionId],
  );
  const { read, shown, written, afresh, readAfresh } = useVersionContent<
    ReferenceListVersion,
    Part
  >(load, readCount, onWritten, onShown);
  const noting = useAction<ReferenceListVersion>(written, onRefused);
  const listing = useAction<ReferenceListVersion>(written, onRefused);
  const editable = version.acts.has("write");
  const { versionId } = version;
  const list = shown;

  return (
    // Drawn from what is shown rather than what was read: a write landing mid-read neither waits on it nor goes.
    <Async read={read} empty={() => null}>
      {() =>
        list === null ? null : (
          <Fragment key={afresh.count}>
            <ProsePart
              words={NOTE_WORDS}
              said={list.note}
              editable={editable}
              waiting={listing.running || read.loading}
              refusalOf={(typed) => {
                const refused = noteRefused(typed);
                return refused === null ? null : NOTE_REFUSALS[refused];
              }}
              rule={VERSION_ACT_RULES.write}
              action={noting}
              save={(note, signal) =>
                writeNote(
                  groupId,
                  entryId,
                  versionId,
                  list.revision,
                  note,
                  signal,
                )
              }
              focusOnArrival={afresh.from === "note"}
              onReadAfresh={readAfresh("note")}
            />
            <TermsTable
              terms={list.terms}
              editable={editable}
              waiting={noting.running || read.loading}
              rule={VERSION_ACT_RULES.write}
              action={listing}
              changes={{
                add: (worded, signal) =>
                  addTerm(
                    groupId,
                    entryId,
                    versionId,
                    list.revision,
                    worded,
                    signal,
                  ),
                reword: (termId, worded, signal) =>
                  rewordTerm(
                    groupId,
                    entryId,
                    versionId,
                    termId,
                    list.revision,
                    worded,
                    signal,
                  ),
                remove: (termId, signal) =>
                  removeTerm(
                    groupId,
                    entryId,
                    versionId,
                    termId,
                    list.revision,
                    signal,
                  ),
                move: (termId, way, signal) =>
                  moveTerm(
                    groupId,
                    entryId,
                    versionId,
                    termId,
                    way,
                    list.revision,
                    signal,
                  ),
              }}
              focusOnArrival={afresh.from === "terms"}
              onReadAfresh={readAfresh("terms")}
            />
          </Fragment>
        )
      }
    </Async>
  );
}
