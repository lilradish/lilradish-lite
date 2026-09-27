// Every other module under api/ is one endpoint, at the path its URL names; a vocabulary several
// endpoints share lives in the one nearest the root.

import type { Unchecked } from "../lib/request/document";

/**
 * The RFC 9457 document the server answers a refusal with, as this side reads
 * it. `code` is the stable half and is never absent — the server stamps one on
 * every response, falling back to the status name when no error code was
 * declared. `detail` is English for whoever reads the response; nothing may
 * branch on it.
 */
export interface Problem {
  /**
   * Absent when no response was reached at all, which makes it the test for
   * whether a problem was served or minted here: read it first, and compare
   * `code` only within the branch that has none. Comparing `code` across both
   * is what lets a served code collide with a local one.
   *
   * Taken from the response rather than the document's own `status`: RFC 9457
   * §3.1.2 makes that member advisory and states that generic HTTP software
   * goes on using the transport's, which is the judgement every cache and
   * proxy on the path is already making.
   */
  readonly status?: number;
  readonly code: string;
  readonly detail?: string;
  readonly errors?: readonly ProblemError[];
  /**
   * Every member beyond RFC 9457's own and this system's, as it arrived and
   * unchecked: only a reader that knows the refusal reads one, and nothing
   * renders them as they came. Absent where none arrived.
   */
  readonly extensions?: Unchecked;
}

/** RFC 9457's members and this system's two, each read above or deliberately not at all. */
const READ_HERE: ReadonlySet<string> = new Set([
  "type",
  "title",
  "status",
  "detail",
  "instance",
  "code",
  "errors",
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

/**
 * Only bean validation produces these, so a complaint that names the value to
 * fix is the one kind of refusal that can.
 */
export interface ProblemError {
  readonly detail: string;
  /**
   * `pointer` is a bare RFC 6901 JSON Pointer (`/items/0/name`), not a URI
   * fragment: nothing is percent-encoded, so never hand it to
   * `decodeURIComponent`. Within a member name `/` is `~1` and `~` is `~0`,
   * and decoding must undo `~1` first or `~01` becomes `/`.
   *
   * `parameter` carries path variables too. The three keys are JSON:API's, and
   * it has no member for a path segment, so there is nowhere else to put one.
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
/** A request sent that no response came back to. */
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
 * Whatever a rejected promise carried, made renderable. An abort is this side's
 * own doing and is reported as a state rather than as a refusal; the signal is
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
 * The refusal a body carries, or null where it carries none. Shape rather than
 * `Content-Type`: this assumes nothing about who wrote the response, so a
 * proxy's own JSON error body is judged by the same rule as the application's.
 *
 * Only `code` can refuse the document, because it is the one member that
 * becomes a sentence for the reader. `detail` and `errors` are advisory, and a
 * malformed one is dropped rather than taken down with the refusal: a fourth
 * kind of `source` is already expected (see `ProblemError`), and on the day it
 * arrives every refusal carrying one would otherwise read as unreadable.
 *
 * Built rather than spread, so nothing unverified can reach the screen. Which
 * is why the document's own `status` is not among what is read here: the
 * response's is stamped by the caller, for the reason `Problem.status` gives.
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
  const read: Problem =
    complaints.length === 0
      ? { code: body.code, detail }
      : { code: body.code, detail, errors: complaints };
  const extensions = Object.fromEntries(
    Object.entries(body).filter(([member]) => !READ_HERE.has(member)),
  );
  return Object.keys(extensions).length === 0 ? read : { ...read, extensions };
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

// Every key a source carries is checked, not the first one found: which key is
// rendered is decided elsewhere, by its own order, so admitting a source on one
// key would leave the other free to be anything.
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
