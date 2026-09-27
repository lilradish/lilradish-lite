import { useCallback } from "react";

import type { EntryKind, Version } from "../../api/groups/{groupId}/{kind}";
import type { ReferenceListVersion } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import type { Problem } from "../../api/problem";
import { contentOf, type ReadContent } from "./ContentProblems";
import { QuestionContent } from "./QuestionContent";
import { ReferenceListContent } from "./ReferenceListContent";
import { WorkflowContent } from "./WorkflowContent";

/**
 * The version picked, drawn by its kind's editor and nothing around it. Drawn afresh for every version picked,
 * so nothing typed into one is carried to the next.
 */
export function VersionContent({
  kind,
  groupId,
  entryId,
  version,
  readCount,
  onRefused,
  onWritten,
  onShown,
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
}) {
  const listShown = useCallback(
    (list: ReferenceListVersion | null) =>
      onShown(list === null ? null : contentOf(list)),
    [onShown],
  );
  switch (kind) {
    case "question":
      return (
        <QuestionContent
          key={version.versionId}
          groupId={groupId}
          entryId={entryId}
          version={version}
          readCount={readCount}
          onRefused={onRefused}
          onWritten={onWritten}
          onShown={onShown}
        />
      );
    case "workflow":
      return (
        <WorkflowContent
          key={version.versionId}
          groupId={groupId}
          entryId={entryId}
          version={version}
          readCount={readCount}
          onRefused={onRefused}
          onWritten={onWritten}
          onShown={onShown}
        />
      );
    case "reference_list":
      return (
        <ReferenceListContent
          key={version.versionId}
          groupId={groupId}
          entryId={entryId}
          version={version}
          readCount={readCount}
          onRefused={onRefused}
          onWritten={onWritten}
          onShown={listShown}
        />
      );
    default: {
      // A kind added without a case here fails to compile rather than drawing nothing.
      const unhandled: never = kind;
      return unhandled;
    }
  }
}
