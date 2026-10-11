import type {
  EntryKind,
  VersionStanding,
} from "../../api/groups/{groupId}/{kind}";
import {
  QUESTIONS_ITEM,
  REFERENCE_LISTS_ITEM,
  WORKFLOWS_ITEM,
  type GroupItem,
} from "../../app/destinations";
import { say, type MessageId } from "../../i18n/app";

export interface LibraryKind {
  readonly kind: EntryKind;
  readonly item: GroupItem;
  /** Names the group. */
  readonly heading: MessageId;
  readonly label: MessageId;
  readonly empty: MessageId;
  /** Names the button that makes one, and the dialog it opens. */
  readonly newButton: MessageId;
}

export const LIBRARY_KINDS = {
  workflow: {
    kind: "workflow",
    item: WORKFLOWS_ITEM,
    heading: "library.workflows",
    label: "library.workflowsLabel",
    empty: "library.noWorkflows",
    newButton: "library.newWorkflow",
  },
  question: {
    kind: "question",
    item: QUESTIONS_ITEM,
    heading: "library.questions",
    label: "library.questionsLabel",
    empty: "library.noQuestions",
    newButton: "library.newQuestion",
  },
  reference_list: {
    kind: "reference_list",
    item: REFERENCE_LISTS_ITEM,
    heading: "library.referenceLists",
    label: "library.referenceListsLabel",
    empty: "library.noReferenceLists",
    newButton: "library.newReferenceList",
  },
} as const satisfies { readonly [K in EntryKind]: LibraryKind & { kind: K } };

const KINDS = {
  workflow: "entryKind.workflow",
  question: "entryKind.question",
  reference_list: "entryKind.reference_list",
} satisfies Record<EntryKind, MessageId>;

const STANDINGS = {
  draft: "version.draft",
  submitted: "version.submitted",
  in_service: "version.in_service",
  retired: "version.retired",
} satisfies Record<VersionStanding, MessageId>;

/** The page a kind has, or none for a kind this build has no page for. */
export function pageOf(kind: string): LibraryKind | undefined {
  return Object.hasOwn(LIBRARY_KINDS, kind)
    ? LIBRARY_KINDS[kind as EntryKind]
    : undefined;
}

/** A kind this build has no words for is shown as it was spelt, rather than hidden. */
export function kindSaid(kind: string): string {
  return Object.hasOwn(KINDS, kind) ? say(KINDS[kind as EntryKind]) : kind;
}

/** A standing this build has no words for is shown as it was spelt, rather than hidden. */
export function standingSaid(standing: string): string {
  return Object.hasOwn(STANDINGS, standing)
    ? say(STANDINGS[standing as VersionStanding])
    : standing;
}
