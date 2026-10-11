import Box from "@mui/material/Box";

import type { Problem, ProblemError } from "../api/problem";
import { say } from "../i18n/app";
import { Notice } from "../lib/notice/Notice";
import { refusalSentence } from "./refusal";
import type { Rule } from "./standing/actRules";

const DISCLOSURE_SX = {
  mt: 1,
  typography: "body2",
  "& summary": { cursor: "pointer" },
  "& dl": {
    display: "grid",
    gridTemplateColumns: "max-content 1fr",
    gap: "0.25rem 1rem",
    m: "0.5rem 0 0",
  },
  "& dt": { typography: "term" },
  "& dd": { m: 0, overflowWrap: "anywhere" },
  "& ul": { m: "0.5rem 0 0", pl: "1.5rem" },
  // A margin rather than a space written beside the complaint: a space is not
  // children, so React would render it even where nothing points anywhere.
  "& li code": { mr: 0.5 },
};

function Source({ source }: { readonly source: ProblemError["source"] }) {
  if (source === undefined) {
    return null;
  }
  if ("pointer" in source) {
    return <code>{source.pointer}</code>;
  }
  if ("header" in source) {
    return <code>{source.header}</code>;
  }
  return <code>{source.parameter}</code>;
}

/**
 * Only stable `code` selects the user's sentence; nothing may branch on `detail`.
 * Developer details stay in a disclosure with the code and debugging context.
 *
 * An alert announces the refusal on render as well as change. It holds only
 * the sentence: its assertive, atomic semantics would otherwise announce the
 * whole developer disclosure when the reader merely opened it.
 */
export function ProblemView({
  problem,
  rule,
  searchWay,
}: {
  readonly problem: Problem;
  /** What the refused request asked, where the caller knows it. */
  readonly rule?: Rule;
  readonly searchWay?: string;
}) {
  const errors = problem.errors ?? [];

  return (
    <Box sx={{ mb: 2 }}>
      <Notice severity="error" alert>
        {refusalSentence(problem, rule, searchWay)}
      </Notice>
      <Box component="details" sx={DISCLOSURE_SX}>
        <summary>{say("failure.disclosure")}</summary>
        <dl>
          <dt>{say("failure.code")}</dt>
          <dd>
            <code>{problem.code}</code>
          </dd>
          {problem.status === undefined ? null : (
            <>
              <dt>{say("failure.status")}</dt>
              <dd>
                <code>{problem.status}</code>
              </dd>
            </>
          )}
          {problem.detail === undefined ? null : (
            <>
              <dt>{say("failure.detail")}</dt>
              <dd>{problem.detail}</dd>
            </>
          )}
        </dl>
        {errors.length === 0 ? null : (
          <ul>
            {/* Keyed by position: nothing here identifies a complaint, and two
                constraints on one value can be refused in the same words. */}
            {errors.map((error, at) => (
              <li key={at}>
                <Source source={error.source} />
                {error.detail}
              </li>
            ))}
          </ul>
        )}
      </Box>
    </Box>
  );
}
