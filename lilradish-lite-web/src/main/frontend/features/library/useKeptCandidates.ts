import { useCallback, useMemo, useRef, useState } from "react";

import type { KeptCandidate } from "./DeclarationBuilder";
import type { StepDraft } from "./workflowDrafts";

type Kind = StepDraft["runs"]["kind"];

interface KeptEntry {
  readonly step: string;
  readonly kind: Kind;
  readonly candidate: KeptCandidate;
}

/** What a step's drawer lends the declarations in it whose changes it may shut away before they are taken in. */
export interface Keeping {
  readonly kept: (scope: string) => KeptCandidate | undefined;
  readonly keep: (scope: string, candidate: KeptCandidate | null) => void;
}

/**
 * The one key a candidate is kept under: its step, what the step runs and the part, the rest of the scope it was
 * kept from after `head`, so neither moving the step nor another part's save moves it.
 */
function keptKeyOf(step: string, kind: Kind, head: string, scope: string) {
  const part = scope.startsWith(head) ? scope.slice(head.length) : scope;
  return JSON.stringify([step, kind, part]);
}

/**
 * Field changes made in a step's drawer and not yet taken in, kept while the drawer is shut. What is kept is held
 * aside from rendering: only which steps hold any, and what each runs, is state.
 */
export function useKeptCandidates() {
  const entries = useRef(new Map<string, KeptEntry>());
  const [holding, setHolding] = useState<ReadonlyMap<string, Kind>>(
    () => new Map(),
  );
  const published = useRef(holding);
  const publish = useCallback(() => {
    const next = new Map(
      [...entries.current.values()].map((entry) => [entry.step, entry.kind]),
    );
    const now = published.current;
    if (
      next.size === now.size &&
      [...next].every(([step, kind]) => now.get(step) === kind)
    )
      return;
    published.current = next;
    setHolding(next);
  }, []);
  /** Lets go of each candidate `dropped` picks, answering whether there was any. */
  const dropWhere = useCallback(
    (dropped: (entry: KeptEntry) => boolean) => {
      const before = entries.current.size;
      for (const [key, entry] of entries.current)
        if (dropped(entry)) entries.current.delete(key);
      publish();
      return entries.current.size < before;
    },
    [publish],
  );
  const lend = useCallback(
    (step: string, kind: Kind, head: string): Keeping => ({
      kept: (scope) =>
        entries.current.get(keptKeyOf(step, kind, head, scope))?.candidate,
      keep: (scope, candidate) => {
        const key = keptKeyOf(step, kind, head, scope);
        if (candidate === null) entries.current.delete(key);
        else entries.current.set(key, { step, kind, candidate });
        publish();
      },
    }),
    [publish],
  );
  /** Asks each candidate of the step to open its field again as its drawer opens. */
  const reopen = useCallback((step: string) => {
    for (const [key, entry] of entries.current)
      if (entry.step === step)
        entries.current.set(key, {
          ...entry,
          candidate: { ...entry.candidate, opening: true },
        });
  }, []);
  const forget = useCallback(
    (step: string) => dropWhere((entry) => entry.step === step),
    [dropWhere],
  );
  const forgetAll = useCallback(() => dropWhere(() => true), [dropWhere]);
  /** Lets go of what is kept for a step no longer there, or no longer running what it was kept under. */
  const prune = useCallback(
    (steps: readonly StepDraft[]) => {
      const kinds = new Map(steps.map((step) => [step.key, step.runs.kind]));
      dropWhere((entry) => kinds.get(entry.step) !== entry.kind);
    },
    [dropWhere],
  );
  /**
   * Carries what is kept over to the keys a save answered, by place; one whose place is not answered goes, and
   * whether any went is answered.
   */
  const rekey = useCallback(
    (keys: readonly string[], answered: readonly (string | undefined)[]) => {
      const carried = new Map<string, KeptEntry>();
      for (const [key, entry] of entries.current) {
        const step = answered[keys.indexOf(entry.step)];
        if (step === undefined) continue;
        const [, kind, part] = JSON.parse(key) as [string, Kind, string];
        carried.set(JSON.stringify([step, kind, part]), { ...entry, step });
      }
      const dropped = carried.size < entries.current.size;
      entries.current = carried;
      publish();
      return dropped;
    },
    [publish],
  );
  return useMemo(
    () => ({ holding, lend, reopen, forget, forgetAll, prune, rekey }),
    [holding, lend, reopen, forget, forgetAll, prune, rekey],
  );
}

/** The steps holding a kept candidate their drawer would read, each still there and running what it was kept under. */
export function keptStepsOf(
  holding: ReadonlyMap<string, Kind>,
  steps: readonly StepDraft[] | undefined,
): ReadonlySet<string> {
  return new Set(
    (steps ?? [])
      .filter((step) => holding.get(step.key) === step.runs.kind)
      .map((step) => step.key),
  );
}
