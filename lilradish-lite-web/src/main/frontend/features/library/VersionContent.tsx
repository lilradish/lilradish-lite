import { useCallback, type ReactNode } from "react";

import type { EntryKind, Version } from "../../api/groups/{groupId}/{kind}";
import type { ReferenceListVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Problem } from "../../api/problem";
import { useStanding } from "../../app/standing/StandingContext";
import { versionOffers } from "./actRules";
import { contentOf, type ReadContent } from "./ContentProblems";
import { QuestionContent } from "./QuestionContent";
import { ReferenceListContent } from "./ReferenceListContent";
import { WorkflowContent } from "./WorkflowContent";

/** Drawn afresh for every version picked, so nothing typed into one is carried to the next. */
export function VersionContent({
  kind,
  groupId,
  entryId,
  version,
  readCount,
  onRefused,
  onWritten,
  onShown,
  wrapDocument,
}: {
  readonly kind: EntryKind;
  readonly groupId: string;
  readonly entryId: string;
  readonly version: Version;
  /** Changed each time the page reads the entry again. */
  readonly readCount: number;
  /** What a write of the content was refused with, for the page to read again after. */
  readonly onRefused: (problem: Problem) => void;
  readonly onWritten: () => void;
  /** The content as last shown, which the page names a refusal's places from. */
  readonly onShown: (content: ReadContent | null) => void;
  /** The document around the content; a workflow's panel stands outside it. */
  readonly wrapDocument?: (content: ReactNode) => ReactNode;
}) {
  const standing = useStanding();
  const editable = versionOffers(version, "write", standing, groupId);
  const listShown = useCallback(
    (list: ReferenceListVersion | null) =>
      onShown(list === null ? null : contentOf(list)),
    [onShown],
  );
  const wrap = (content: ReactNode) =>
    wrapDocument === undefined ? content : wrapDocument(content);
  switch (kind) {
    case "question":
      return wrap(
        <QuestionContent
          key={version.versionId}
          groupId={groupId}
          entryId={entryId}
          version={version}
          editable={editable}
          readCount={readCount}
          onRefused={onRefused}
          onWritten={onWritten}
          onShown={onShown}
        />,
      );
    case "workflow":
      return (
        <WorkflowContent
          wrapDocument={wrapDocument}
          key={version.versionId}
          groupId={groupId}
          entryId={entryId}
          version={version}
          editable={editable}
          readCount={readCount}
          onRefused={onRefused}
          onWritten={onWritten}
          onShown={onShown}
        />
      );
    case "reference_list":
      return wrap(
        <ReferenceListContent
          key={version.versionId}
          groupId={groupId}
          entryId={entryId}
          version={version}
          editable={editable}
          readCount={readCount}
          onRefused={onRefused}
          onWritten={onWritten}
          onShown={listShown}
        />,
      );
    default: {
      // A kind added without a case here fails to compile rather than drawing nothing.
      const unhandled: never = kind;
      return unhandled;
    }
  }
}
