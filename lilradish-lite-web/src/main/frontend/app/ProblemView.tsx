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
  "& code": { typography: "identifier" },
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

/** Where a complaint points, in whichever of the three ways it can say so. */
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
 * A refusal, said to whoever is looking at it.
 *
 * The document carries two things with two audiences. `code` is the stable one
 * and is what this side turns into a sentence a person can act on; `detail` is
 * written for whoever reads the response — a developer with a log or a terminal
 * — and the contract says outright that nothing may branch on it. Putting that
 * in front of the reader is this screen reading the developer half out loud,
 * so it is one disclosure away, together with the code and the narrow reasons
 * anyone debugging will ask for first.
 *
 * Announced as an alert: it is the live region announced when
 * it is rendered rather than only when its content changes, and a refusal that
 * has replaced what the reader asked for has to be read out either way. The
 * sentence is the whole of what that region holds — the disclosure is a sibling
 * outside it, because `alert` is assertive and atomic, so opening it inside
 * would announce the entire developer half, headline first, at the reader who
 * only wanted to look at it.
 */
export function ProblemView({
  problem,
  rule,
}: {
  readonly problem: Problem;
  /** What the refused request asked, where the caller knows it. */
  readonly rule?: Rule;
}) {
  const errors = problem.errors ?? [];

  return (
    <Box sx={{ mb: 2 }}>
      <Notice severity="error" alert>
        {refusalSentence(problem, rule)}
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
