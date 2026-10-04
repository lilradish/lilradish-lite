import { useCallback, useState } from "react";

import type { Problem } from "../../api/problem";
import type { Page } from "./page";
import { useResource } from "./useResource";

interface Request<T> {
  readonly load: (
    cursor: string | null,
    signal: AbortSignal,
  ) => Promise<Page<T>>;
  readonly cursor: string | null;
  readonly number: number;
}

interface Held<T> {
  readonly load: Request<T>["load"];
  readonly items: readonly T[];
  readonly nextCursor?: string;
  readonly answered: number | null;
  readonly terminalProblem: Problem | null;
}

interface Answer<T> {
  readonly load: Request<T>["load"];
  readonly number: number;
  readonly page: Page<T>;
}

function firstRequest<T>(load: Request<T>["load"]): Request<T> {
  return { load, cursor: null, number: 0 };
}

function emptyHeld<T>(load: Request<T>["load"]): Held<T> {
  return { load, items: [], answered: null, terminalProblem: null };
}

export interface AccumulatedResource<T> {
  readonly items: readonly T[];
  readonly loading: boolean;
  readonly problem: Problem | null;
  readonly terminal: boolean;
  readonly hasNext: boolean;
  readonly next: () => void;
  readonly retry: () => void;
  readonly reload: () => void;
}

/**
 * Keeps accepted pages under one source. A new source takes its rows away in
 * render, before an effect could leave them under another reader's heading.
 */
export function useAccumulatedResource<T>(
  load: Request<T>["load"],
  closes: (problem: Problem) => boolean,
): AccumulatedResource<T> {
  const [request, setRequest] = useState(() => firstRequest(load));
  const [held, setHeld] = useState(() => emptyHeld(load));
  const changed = request.load !== load;
  const current = changed ? firstRequest(load) : request;
  const shown = changed || held.load !== load ? emptyHeld(load) : held;
  const read = useCallback(
    (signal: AbortSignal): Promise<Answer<T>> =>
      current.load(current.cursor, signal).then((page) => ({
        load: current.load,
        number: current.number,
        page,
      })),
    [current],
  );
  const page = useResource<Answer<T> | null>(read, null);

  if (changed) {
    setRequest(current);
    setHeld(shown);
  }

  const terminalProblem =
    shown.terminalProblem ??
    (page.problem !== null && closes(page.problem) ? page.problem : null);
  if (terminalProblem !== null && shown.terminalProblem === null && !changed) {
    setHeld({ ...shown, terminalProblem });
  }

  const answer = page.value;
  if (
    !changed &&
    terminalProblem === null &&
    !page.loading &&
    page.problem === null &&
    answer !== null &&
    answer.load === load &&
    answer.number === current.number &&
    shown.answered !== current.number
  ) {
    setHeld({
      load,
      items: [...shown.items, ...answer.page.items],
      nextCursor: answer.page.nextCursor,
      answered: current.number,
      terminalProblem: null,
    });
  }

  const next = useCallback(() => {
    const nextCursor = shown.nextCursor;
    if (
      page.loading ||
      page.problem !== null ||
      terminalProblem !== null ||
      typeof nextCursor !== "string"
    ) {
      return;
    }
    setRequest((prior) =>
      prior === current
        ? { load, cursor: nextCursor, number: prior.number + 1 }
        : prior,
    );
  }, [
    current,
    load,
    page.loading,
    page.problem,
    shown.nextCursor,
    terminalProblem,
  ]);

  const retry = useCallback(() => {
    if (page.problem === null || terminalProblem !== null) {
      return;
    }
    setRequest((prior) =>
      prior === current ? { ...prior, number: prior.number + 1 } : prior,
    );
  }, [current, page.problem, terminalProblem]);

  const reload = useCallback(() => {
    if (terminalProblem !== null) {
      return;
    }
    page.reload();
    setHeld(emptyHeld(load));
    setRequest((prior) => ({ load, cursor: null, number: prior.number + 1 }));
  }, [load, page, terminalProblem]);

  return {
    items: terminalProblem === null ? shown.items : [],
    loading: terminalProblem === null && page.loading,
    problem: terminalProblem ?? page.problem,
    terminal: terminalProblem !== null,
    hasNext: terminalProblem === null && typeof shown.nextCursor === "string",
    next,
    retry,
    reload,
  };
}
