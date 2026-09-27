import { describe, expect, it } from "vitest";

import {
  asProblem,
  BROKEN_HERE,
  NOT_A_PROBLEM_DOCUMENT,
  NOT_AN_ADDRESS,
  NOT_REACHED,
  requestFailed,
  RequestFailed,
  servedAmong,
  STOPPED_HERE,
} from "./problem";

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

function abortedSignal(): AbortSignal {
  const controller = new AbortController();
  controller.abort();
  return controller.signal;
}

function liveSignal(): AbortSignal {
  return new AbortController().signal;
}

describe("locally minted codes", () => {
  it.each([
    STOPPED_HERE,
    NOT_A_PROBLEM_DOCUMENT,
    NOT_AN_ADDRESS,
    NOT_REACHED,
    BROKEN_HERE,
  ])(
    "stays inside the lowercase namespace a served code never uses: %s",
    (code) => {
      expect(code).toMatch(/^[a-z_]+$/);
    },
  );
});

describe("RequestFailed", () => {
  it("reads its message from the detail the server wrote", () => {
    const failure = new RequestFailed({
      status: 403,
      code: "SOME_REFUSAL",
      detail: "This is not permitted here",
    });

    expect(failure.message).toBe("This is not permitted here");
  });

  it("falls back to the code when the detail is present but empty", () => {
    const failure = new RequestFailed({
      status: 500,
      code: "INTERNAL",
      detail: "",
    });

    expect(failure.message).toBe("INTERNAL");
    expect(failure.message).not.toBe("");
  });

  it("keeps the underlying failure as its cause", () => {
    const parseFailure = new SyntaxError("Unexpected end of JSON input");

    const failure = new RequestFailed(
      { status: 502, code: NOT_A_PROBLEM_DOCUMENT },
      { cause: parseFailure },
    );

    expect(failure.cause).toBe(parseFailure);
  });
});

describe("requestFailed", () => {
  it("carries the served code, detail and errors through", async () => {
    const response = jsonResponse(
      {
        code: "BAD_REQUEST",
        detail: "Two fields were rejected",
        errors: [{ detail: "must not be blank", source: { pointer: "/name" } }],
      },
      400,
    );

    const { problem } = await requestFailed(response);

    expect(problem.code).toBe("BAD_REQUEST");
    expect(problem.detail).toBe("Two fields were rejected");
    expect(problem.errors).toEqual([
      { detail: "must not be blank", source: { pointer: "/name" } },
    ]);
  });

  /**
   * The guard has to let the whole union through, not the one arm the first
   * test happened to use: a header and a parameter complaint are what method
   * validation produces, and rejecting them would turn a refusal that names the
   * value to fix into one that says nothing.
   */
  it("reads a complaint pointing at a header or a parameter as readily as at a field", async () => {
    const response = jsonResponse(
      {
        code: "BAD_REQUEST",
        errors: [
          { detail: "must be present", source: { header: "X-Purpose" } },
          { detail: "must be positive", source: { parameter: "limit" } },
          { detail: "the window has to start before it ends" },
        ],
      },
      400,
    );

    const { problem } = await requestFailed(response);

    expect(problem.code).toBe("BAD_REQUEST");
    expect(problem.errors).toHaveLength(3);
  });

  /** What only the refusal's own reader knows arrives as it came, and nothing the document names twice. */
  it("keeps every member beyond the document's own apart and unchecked, and holds none where none arrived", async () => {
    const [extended, plain] = await Promise.all([
      requestFailed(
        jsonResponse(
          {
            type: "about:blank",
            title: "Conflict",
            status: 409,
            detail: "Pins a version retired since.",
            instance: "/api/x",
            code: "VERSION_PINS_RETIRED",
            pins: [{ name: "Regions" }],
          },
          409,
        ),
      ),
      requestFailed(
        jsonResponse(
          { type: "about:blank", title: "Conflict", code: "X", instance: "/" },
          409,
        ),
      ),
    ]);

    expect(extended.problem.extensions).toEqual({
      pins: [{ name: "Regions" }],
    });
    expect("extensions" in plain.problem).toBe(false);
  });

  it("takes the status from the response rather than from the document", async () => {
    const response = jsonResponse({ code: "SOME_REFUSAL", status: 500 }, 404);

    const { problem } = await requestFailed(response);

    expect(problem.status).toBe(404);
  });

  it("keeps the refusal and drops a detail that is not a sentence", async () => {
    const response = jsonResponse({ code: "INTERNAL", detail: { at: 1 } }, 500);

    const { problem } = await requestFailed(response);

    expect(problem.code).toBe("INTERNAL");
    expect(problem.detail).toBeUndefined();
  });

  /**
   * `code` is the one member that becomes a sentence a reader can act on, and
   * `errors` is advisory. A complaint this side cannot read costs the reader
   * that complaint; letting it cost them the code as well would answer any
   * served refusal's own sentence with "in a way this side cannot read".
   *
   * The last row is the one no single-key fixture can see: whichever key is
   * rendered is decided away from here, so a source admitted on the key it
   * happens to be checked on leaves the other free to be an object.
   */
  it.each([
    ["errors sent as one string", "boom"],
    ["a complaint list holding nothing", [null]],
    ["a complaint with no sentence of its own", [{ source: { header: "X" } }]],
    ["a complaint pointing at nothing the union names", [{ d: 1 }]],
    [
      "a complaint whose source names none of the three keys",
      [{ detail: "must not be blank", source: {} }],
    ],
    [
      "a pointer that is not a string",
      [{ detail: "no", source: { pointer: 7 } }],
    ],
    [
      "a complaint naming where it points in prose",
      [{ detail: "no", source: "the note field" }],
    ],
    [
      "a source carrying one key this side reads and one it does not",
      [
        {
          detail: "must be positive",
          source: { pointer: { at: 1 }, parameter: "limit" },
        },
      ],
    ],
  ])("keeps the refusal and drops %s", async (_case, errors) => {
    const response = jsonResponse(
      {
        code: "SOME_REFUSAL",
        detail: "refused for a reason of the server's own",
        errors,
      },
      403,
    );

    const { problem } = await requestFailed(response);

    expect(problem.code).toBe("SOME_REFUSAL");
    expect(problem.detail).toBe("refused for a reason of the server's own");
    expect(problem.errors).toBeUndefined();
  });

  it("keeps the complaints it can read and discards only the ones it cannot", async () => {
    const response = jsonResponse(
      {
        code: "BAD_REQUEST",
        errors: [
          { detail: "must not be blank", source: { pointer: "/note" } },
          { detail: "no source at all", source: {} },
          { detail: "must be present", source: { header: "X-Purpose" } },
        ],
      },
      400,
    );

    const { problem } = await requestFailed(response);

    expect(problem.errors).toEqual([
      { detail: "must not be blank", source: { pointer: "/note" } },
      { detail: "must be present", source: { header: "X-Purpose" } },
    ]);
  });

  it.each([
    ["a document carrying no code", { detail: "something went wrong" }],
    ["a code that is not a string", { code: 502 }],
    ["a JSON array", ["not", "a", "document"]],
    ["a JSON scalar", "gateway timeout"],
  ])("refuses to read %s as a refusal", async (_case, body) => {
    const response = jsonResponse(body, 502);

    const failure = await requestFailed(response);

    expect(failure.problem.code).toBe(NOT_A_PROBLEM_DOCUMENT);
    expect(failure.problem.detail).toBeUndefined();
    expect(failure.cause).toBeUndefined();
  });

  it("keeps the parse failure as the cause when the body is not JSON at all", async () => {
    const response = new Response("<html>Gateway Timeout</html>", {
      status: 504,
      headers: { "Content-Type": "text/html" },
    });

    const failure = await requestFailed(response);

    expect(failure.problem.code).toBe(NOT_A_PROBLEM_DOCUMENT);
    expect(failure.problem.status).toBe(504);
    expect(failure.cause).toBeInstanceOf(Error);
  });

  it("keeps the status of a refusal answered with an empty body", async () => {
    const response = new Response(null, { status: 404 });

    const failure = await requestFailed(response);

    expect(failure.problem.status).toBe(404);
    expect(failure.problem.code).toBe(NOT_A_PROBLEM_DOCUMENT);
  });

  it("gives a status to every problem it builds, however the body degraded", async () => {
    const built = await Promise.all([
      requestFailed(jsonResponse({ code: "SOME_REFUSAL" }, 404)),
      requestFailed(jsonResponse({ detail: "no code here" }, 502)),
      requestFailed(
        new Response("<html>Gateway Timeout</html>", { status: 504 }),
      ),
      requestFailed(new Response(null, { status: 404 })),
    ]);

    expect(built.map((failure) => failure.problem.status)).toEqual([
      404, 502, 504, 404,
    ]);
  });
});

describe("servedAmong", () => {
  const CODES = new Set(["ENTRY_NOT_IN_VIEW"]);

  it.each([
    [
      "a served code among them",
      { status: 404, code: "ENTRY_NOT_IN_VIEW" },
      true,
    ],
    [
      "a served code among none of them",
      { status: 404, code: "NOT_FOUND" },
      false,
    ],
    [
      "a code of that spelling minted here",
      { code: "ENTRY_NOT_IN_VIEW" },
      false,
    ],
  ])("answers %s", (_case, problem, among) => {
    expect(servedAmong(problem, CODES)).toBe(among);
  });
});

describe("asProblem", () => {
  it("reports an abort as this side's own state, with no status", () => {
    const problem = asProblem(new Error("aborted"), abortedSignal());

    expect(problem.code).toBe(STOPPED_HERE);
    expect(problem.status).toBeUndefined();
    expect(problem.detail).toBeUndefined();
  });

  it("reads the signal ahead of the failure, so an abort is never read as a refusal", () => {
    const served = new RequestFailed({
      status: 403,
      code: "SOME_REFUSAL",
    });

    const problem = asProblem(served, abortedSignal());

    expect(problem.code).toBe(STOPPED_HERE);
    expect(problem.code).not.toBe("SOME_REFUSAL");
  });

  it("passes a served refusal through untouched", () => {
    const served = new RequestFailed({
      status: 403,
      code: "OTHER_REFUSAL",
      detail: "That was refused",
    });

    const problem = asProblem(served, liveSignal());

    expect(problem).toBe(served.problem);
  });

  it.each([
    [
      "an Error",
      new TypeError("Cannot read properties of undefined (reading 'id')"),
    ],
    ["anything else thrown", "something threw a string"],
  ])(
    "reads %s as this side's own fault and shows none of what it says",
    (_case, failure) => {
      const problem = asProblem(failure, liveSignal());

      expect(problem).toEqual({ code: BROKEN_HERE });
      expect(problem.code).not.toBe(NOT_A_PROBLEM_DOCUMENT);
    },
  );

  it("mints every problem of its own without a status, so a missing one names this side", () => {
    const minted = [
      asProblem(new Error("aborted"), abortedSignal()),
      asProblem(new TypeError("Failed to fetch"), liveSignal()),
      asProblem("something threw a string", liveSignal()),
    ];

    expect(minted.filter((problem) => problem.status !== undefined)).toEqual(
      [],
    );
  });
});
