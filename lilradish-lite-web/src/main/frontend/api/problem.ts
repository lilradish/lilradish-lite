// Every other module under api/ is one endpoint, at the path its URL names; a vocabulary several
// endpoints share lives in the one nearest the root.

import { listOf, textFrom, type Unchecked } from "../lib/request/document";

/**
 * The RFC 9457 document the server answers a refusal with, as this side reads
 * it. `code` is the stable half and is never absent — the server stamps one on
 * every response, falling back to the status name when no error code was
 * declared. `detail` is English for whoever reads the response; nothing may
 * branch on it.
 */
export interface Problem {
  /**
   * Absent without a response: branch on it before comparing local codes,
   * which may collide with served ones.
   * Taken from the response: RFC 9457 §3.1.2 makes the document's status advisory;
   * generic HTTP software, including caches and proxies, uses the transport's.
   */
  readonly status?: number;
  readonly code: string;
  readonly detail?: string;
  readonly errors?: readonly ProblemError[];
  readonly names?: readonly string[];
  /**
   * Every member beyond RFC 9457's own and this system's, as it arrived and
   * unchecked: only a reader that knows the refusal reads one, and nothing
   * renders them as they came. Absent where none arrived.
   */
  readonly extensions?: Unchecked;
}

const READ_HERE: ReadonlySet<string> = new Set([
  "type",
  "title",
  "status",
  "detail",
  "instance",
  "code",
  "errors",
  "names",
]);

/**
 * Whether the server refused with one of `codes`, the status read before the
 * code, as `Problem` requires: a code minted here is never among them.
 */
export function servedAmong(
  problem: Problem,
  codes: ReadonlySet<string>,
): boolean {
  return problem.status !== undefined && codes.has(problem.code);
}

export interface ProblemError {
  readonly detail: string;
  /**
   * A bare RFC 6901 pointer, never percent-decoded: undo `~1` to `/` before `~0` to `~`.
   * `parameter` also carries path variables: JSON:API has no path-segment source member.
   */
  readonly source?:
    | { readonly pointer: string }
    | { readonly header: string }
    | { readonly parameter: string };
}

/** Minted here. Lowercase: a served code is SCREAMING_SNAKE or digits, never this. */
export const STOPPED_HERE = "stopped_here";
export const NOT_A_PROBLEM_DOCUMENT = "not_a_problem_document";
/** An address this side would not send, it naming nothing the server could answer for. */
export const NOT_AN_ADDRESS = "not_an_address";
export const NOT_REACHED = "not_reached";
/** A fault in this side's own code: reported where every caught error is, and its message shown to nobody. */
export const BROKEN_HERE = "broken_here";

export class RequestFailed extends Error {
  readonly problem: Problem;

  constructor(problem: Problem, options?: ErrorOptions) {
    // `||`, not `??`: a served but empty detail would otherwise blank the
    // message exactly where the server chose to say least.
    super(problem.detail || problem.code, options);
    this.name = "RequestFailed";
    this.problem = problem;
  }
}

/**
 * Not every failing response is written by the application: a proxy, a gateway
 * or the container can answer first, and none of them produce a problem
 * document.
 */
export async function requestFailed(
  response: Response,
): Promise<RequestFailed> {
  let body: unknown;
  try {
    body = await response.json();
  } catch (failure) {
    // An empty body, a truncated one and an HTML error page are one code here;
    // the cause is the only thing that tells them apart afterwards.
    return new RequestFailed(
      { status: response.status, code: NOT_A_PROBLEM_DOCUMENT },
      { cause: failure },
    );
  }
  const refusal = problemFrom(body);
  if (refusal !== null) {
    return new RequestFailed({ ...refusal, status: response.status });
  }
  return new RequestFailed({
    status: response.status,
    code: NOT_A_PROBLEM_DOCUMENT,
  });
}

/**
 * An abort is this side's own doing and is reported as a state rather than as a refusal; the signal is
 * read before the failure because an aborted fetch is refused as one that never
 * came back. Anything but a `RequestFailed` is a fault in this side's code, and
 * its message is for whoever reads the report, not for the reader.
 */
export function asProblem(failure: unknown, signal: AbortSignal): Problem {
  if (signal.aborted) {
    return { code: STOPPED_HERE };
  }
  if (failure instanceof RequestFailed) {
    return failure.problem;
  }
  return { code: BROKEN_HERE };
}

/**
 * Null without a refusal; judged by shape, not Content-Type, so proxy JSON meets the same rule.
 * Only `code`, which becomes the reader's sentence, can refuse the document.
 * Malformed detail or errors are dropped so future source kinds cannot hide the refusal.
 * Build verified members, never spread unchecked ones. The caller stamps response status,
 * excluding the document's advisory status for the reason `Problem.status` gives.
 */
function problemFrom(body: unknown): Problem | null {
  if (
    typeof body !== "object" ||
    body === null ||
    !("code" in body) ||
    typeof body.code !== "string"
  ) {
    return null;
  }
  const detail =
    "detail" in body && typeof body.detail === "string"
      ? body.detail
      : undefined;
  const complaints =
    "errors" in body && Array.isArray(body.errors)
      ? body.errors.filter(isComplaint)
      : [];
  const listedNames = "names" in body ? listOf(body.names, textFrom) : null;
  const names =
    listedNames !== null && listedNames.length > 0 ? listedNames : undefined;
  const read: Problem =
    complaints.length === 0
      ? { code: body.code, detail }
      : { code: body.code, detail, errors: complaints };
  const extensions = Object.fromEntries(
    Object.entries(body).filter(([member]) => !READ_HERE.has(member)),
  );
  const named = names === undefined ? read : { ...read, names };
  return Object.keys(extensions).length === 0
    ? named
    : { ...named, extensions };
}

function isComplaint(complaint: unknown): complaint is ProblemError {
  return (
    typeof complaint === "object" &&
    complaint !== null &&
    "detail" in complaint &&
    typeof complaint.detail === "string" &&
    (!("source" in complaint) || isSource(complaint.source))
  );
}

// Check every source key: rendering chooses its own order elsewhere,
// so checking only the first could admit an unchecked value for display.
function isSource(source: unknown): boolean {
  if (typeof source !== "object" || source === null) {
    return false;
  }
  return (
    ("pointer" in source || "header" in source || "parameter" in source) &&
    !("pointer" in source && typeof source.pointer !== "string") &&
    !("header" in source && typeof source.header !== "string") &&
    !("parameter" in source && typeof source.parameter !== "string")
  );
}
