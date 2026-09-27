import { putDocument } from "../../../../../../../lib/request/http";
import type { SentField } from "../../../../../../declaration";
import { atVersion } from "../../versions";
import {
  workflowFrom,
  type BindingSource,
  type WorkflowVersion,
} from "../{versionId}";

/** What fills one input; a step is read from by its place among the steps sent. */
export interface SentBinding {
  readonly target: string;
  readonly source: BindingSource;
}

/** A question's are a model or a person; a code step's are code or a person saying it was done. */
export type SentProducer =
  | { readonly kind: "person" | "code" }
  | {
      readonly kind: "model";
      readonly model: string;
      readonly mode: string | null;
      readonly toldWhatHappened: boolean;
    };

export interface SentChoice {
  readonly model: string;
  readonly mode: string | null;
}

/** The fallback is the case on no term. */
export interface SentCase {
  readonly caseId: string | null;
  readonly term: string | null;
  readonly workflow: string | null;
  readonly bindings: readonly SentBinding[];
}

export type SentRuns =
  | { readonly kind: "question" | "workflow"; readonly version: string }
  | { readonly kind: "code_step"; readonly codeStep: string | null }
  | {
      readonly kind: "route";
      readonly discriminator: BindingSource | null;
      readonly gives: readonly SentField[];
      readonly cases: readonly SentCase[];
    };

/**
 * One step as it is sent: exactly the members what it runs takes, a producer, tries and a reviewer only where
 * it runs a question or a code step.
 */
export interface SentStep {
  /** The key it was read under, which keeps what it pins; none for a step added. */
  readonly stepId: string | null;
  readonly name: string;
  readonly runs: SentRuns | null;
  readonly producer?: SentProducer | null;
  readonly tries?: number | null;
  readonly reviewer?: SentChoice | null;
  readonly bindings: readonly SentBinding[];
}

/**
 * Every step of a workflow draft in the order it runs, and what fills each value it gives back, written whole in
 * place of what it held and naming the revision the page read; answered with the version as it then reads.
 */
export function writeSteps(
  groupId: string,
  entryId: string,
  versionId: string,
  revision: number,
  steps: readonly SentStep[],
  outputs: readonly SentBinding[],
  signal: AbortSignal,
): Promise<WorkflowVersion> {
  return atVersion(groupId, "workflow", entryId, versionId, (version) =>
    putDocument(
      `${version}/steps`,
      { revision, steps, outputs },
      signal,
      workflowFrom,
    ),
  );
}
