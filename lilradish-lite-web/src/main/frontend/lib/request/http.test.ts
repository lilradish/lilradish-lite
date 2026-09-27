import { describe, expect, it, vi } from "vitest";

import {
  NOT_A_PROBLEM_DOCUMENT,
  NOT_REACHED,
  RequestFailed,
} from "../../api/problem";
import * as published from "./http";
import {
  get,
  patch,
  post,
  postNothing,
  put,
  putDocument,
  remove,
} from "./http";

const PATH = "/api/standing";

/**
 * The real `Response`, never a shape resembling one. `ok`, `status` and `text`
 * each decide something below — a stand-in short of any of those leaves the
 * branch reading it unvisited while the suite stays green.
 */
function replying(response: Response) {
  const sent = vi.fn<typeof fetch>().mockResolvedValue(response);
  vi.stubGlobal("fetch", sent);
  return sent;
}

function document(body: string, status: number): Response {
  return new Response(body, {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function initOf(sent: ReturnType<typeof replying>): RequestInit {
  return sent.mock.calls[0]![1]!;
}

/** A reader taking any document as it came, for the cases about everything but the reading. */
const AS_SENT = (body: unknown) => body;

describe("get", () => {
  it("reads the document the server answered with", async () => {
    replying(document('{"items":[{"id":"9f1c"}]}', 200));

    await expect(get(PATH, undefined, AS_SENT)).resolves.toEqual({
      items: [{ id: "9f1c" }],
    });
  });

  it("raises the server's own refusal, under the status the response carried", async () => {
    replying(document('{"code":"SOME_REFUSAL","detail":"Not yours."}', 403));

    const refusal = await get(PATH, undefined, AS_SENT).catch(
      (failure: unknown) => failure,
    );

    expect(refusal).toBeInstanceOf(RequestFailed);
    expect((refusal as RequestFailed).problem).toEqual({
      status: 403,
      code: "SOME_REFUSAL",
      detail: "Not yours.",
    });
  });

  /**
   * The shape a sign-in gateway answers in: a status saying all is well over a
   * page nobody here can read. A problem without a status is read as a request
   * that never arrived, which would tell the reader to retry a server that had
   * just answered them.
   */
  it("keeps the status of an answer that turned out not to be a document", async () => {
    replying(
      new Response("<html><body>Sign in</body></html>", { status: 200 }),
    );

    const refusal = await get(PATH, undefined, AS_SENT).catch(
      (failure: unknown) => failure,
    );

    expect(refusal).toBeInstanceOf(RequestFailed);
    expect((refusal as RequestFailed).problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("hands a reader the parsed document and answers with what it made of it", async () => {
    replying(document('{"count":2}', 200));
    const read = vi.fn((parsed: unknown) => ({ seen: parsed }));

    await expect(get(PATH, undefined, read)).resolves.toEqual({
      seen: { count: 2 },
    });
    expect(read).toHaveBeenCalledOnce();
  });

  /**
   * The reader cannot say which status the document came under, and without
   * one the refusal would read as a request that never arrived.
   */
  it("refuses a document its reader could not make anything of, under the status it arrived with", async () => {
    replying(document('{"count":"two"}', 203));

    const refusal = await get(PATH, undefined, () => null).catch(
      (failure: unknown) => failure,
    );

    expect((refusal as RequestFailed).problem).toEqual({
      status: 203,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  /**
   * Refused as the house's own failure, so that whatever else a read rejects
   * with is known to be a fault in this side's code.
   */
  it("refuses a request no response came back to as never reached, with no status and the transport's failure as its cause", async () => {
    const unsent = new TypeError("Failed to fetch");
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockRejectedValue(unsent));

    const refusal = await get(PATH, undefined, AS_SENT).catch(
      (failure: unknown) => failure,
    );

    expect(refusal).toBeInstanceOf(RequestFailed);
    expect((refusal as RequestFailed).problem).toEqual({ code: NOT_REACHED });
    expect((refusal as RequestFailed).cause).toBe(unsent);
  });

  it("refuses an answer whose body was cut off on the way as unreadable, under the status it arrived with", async () => {
    const cutOff = new TypeError("terminated");
    replying(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.error(cutOff);
          },
        }),
        { status: 200 },
      ),
    );

    const refusal = await get(PATH, undefined, AS_SENT).catch(
      (failure: unknown) => failure,
    );

    expect(refusal).toBeInstanceOf(RequestFailed);
    expect((refusal as RequestFailed).problem).toEqual({
      status: 200,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
    expect((refusal as RequestFailed).cause).toBe(cutOff);
  });
});

describe("post", () => {
  it("reads the document the server answered a creation with", async () => {
    replying(document('{"id":"9f1c"}', 201));

    await expect(
      post(PATH, { userId: "000140" }, new AbortController().signal, AS_SENT),
    ).resolves.toEqual({ id: "9f1c" });
  });

  it("raises the server's own refusal of what was sent, under the status it carried", async () => {
    replying(document('{"code":"USER_NOT_IN_DIRECTORY","detail":"d"}', 400));

    const refusal = await post(
      PATH,
      { userId: "000999" },
      new AbortController().signal,
      AS_SENT,
    ).catch((failure: unknown) => failure);

    expect((refusal as RequestFailed).problem).toEqual({
      status: 400,
      code: "USER_NOT_IN_DIRECTORY",
      detail: "d",
    });
  });
});

describe("patch", () => {
  it("reads the document the server answered a change with", async () => {
    replying(document('{"name":"Salaries"}', 200));

    await expect(
      patch(PATH, { name: "Salaries" }, new AbortController().signal, AS_SENT),
    ).resolves.toEqual({ name: "Salaries" });
  });

  it("raises the server's own refusal of what was sent, under the status it carried", async () => {
    replying(document('{"code":"GROUP_NAME_TAKEN","detail":"d"}', 409));

    const refusal = await patch(
      PATH,
      { name: "Payroll" },
      new AbortController().signal,
      AS_SENT,
    ).catch((failure: unknown) => failure);

    expect((refusal as RequestFailed).problem).toEqual({
      status: 409,
      code: "GROUP_NAME_TAKEN",
      detail: "d",
    });
  });
});

describe("putDocument", () => {
  it("reads the document the server answered a replacement with", async () => {
    replying(document('{"takes":[]}', 200));

    await expect(
      putDocument(PATH, [], new AbortController().signal, AS_SENT),
    ).resolves.toEqual({ takes: [] });
  });

  it("raises the server's own refusal of what was sent, under the status it carried", async () => {
    replying(document('{"code":"FIELD_NAME_UNUSABLE","detail":"d"}', 400));

    const refusal = await putDocument(
      PATH,
      [{ name: "Complaint" }],
      new AbortController().signal,
      AS_SENT,
    ).catch((failure: unknown) => failure);

    expect((refusal as RequestFailed).problem).toEqual({
      status: 400,
      code: "FIELD_NAME_UNUSABLE",
      detail: "d",
    });
  });
});

describe("remove", () => {
  /**
   * The one answer carrying no document by design: the reader is handed
   * nothing, so the endpoint promising no content is the one that accepts it.
   */
  it("hands a reader nothing where the server answered with no content, and parses nothing", async () => {
    replying(new Response(null, { status: 204 }));
    const read = vi.fn((body: unknown) => (body === undefined ? "gone" : null));

    await expect(
      remove(PATH, new AbortController().signal, read),
    ).resolves.toBe("gone");
    expect(read.mock.calls).toEqual([[undefined]]);
  });

  it("refuses no content where the reader expected a document, under that status", async () => {
    replying(new Response(null, { status: 204 }));

    const refusal = await remove(PATH, new AbortController().signal, (body) =>
      body === undefined ? null : body,
    ).catch((failure: unknown) => failure);

    expect((refusal as RequestFailed).problem).toEqual({
      status: 204,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  });

  it("reads a document where the server answered a removal with one", async () => {
    replying(document('{"roles":[]}', 200));

    await expect(
      remove(PATH, new AbortController().signal, AS_SENT),
    ).resolves.toEqual({ roles: [] });
  });
});

/**
 * What goes out, declared as a table and compared whole. Comparing the entire
 * header set rather than the one header each case is about is the point: a
 * header naming who is asking is a header nobody put in this table, and the
 * browser carries the credential without one being written here.
 */
type RequestKind =
  | "a read"
  | "a document sent in"
  | "a document naming a change"
  | "a document standing in place of another"
  | "a making carrying nothing"
  | "a change carrying nothing"
  | "a removal";

interface Outgoing {
  readonly request: (signal: AbortSignal) => Promise<unknown>;
  /** The function the case puts through its paces, so the table can be closed. */
  readonly of: unknown;
  /** Absent for a read, which is what a request with no method is. */
  readonly method: string | undefined;
  readonly headers: Record<string, string>;
  readonly body: string | undefined;
  /** What the request says about being stored. */
  readonly cache: RequestCache | undefined;
  /** Sorted. Every member of the request, so an unlisted one fails here. */
  readonly members: readonly string[];
}

const REQUESTS = {
  "a read": {
    request: (signal: AbortSignal) => get(PATH, signal, AS_SENT),
    of: get,
    method: undefined,
    headers: { Accept: "application/json" },
    body: undefined,
    cache: "no-store",
    members: ["cache", "headers", "signal"],
  },
  "a document sent in": {
    request: (signal: AbortSignal) =>
      post(PATH, { userId: "000140" }, signal, AS_SENT),
    of: post,
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: '{"userId":"000140"}',
    cache: "no-store",
    members: ["body", "cache", "headers", "method", "signal"],
  },
  "a document naming a change": {
    request: (signal: AbortSignal) =>
      patch(PATH, { name: "Payroll" }, signal, AS_SENT),
    of: patch,
    method: "PATCH",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: '{"name":"Payroll"}',
    cache: "no-store",
    members: ["body", "cache", "headers", "method", "signal"],
  },
  "a document standing in place of another": {
    request: (signal: AbortSignal) =>
      putDocument(PATH, [{ name: "complaint" }], signal, AS_SENT),
    of: putDocument,
    method: "PUT",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: '[{"name":"complaint"}]',
    cache: "no-store",
    members: ["body", "cache", "headers", "method", "signal"],
  },
  "a making carrying nothing": {
    request: (signal: AbortSignal) => postNothing(PATH, signal, AS_SENT),
    of: postNothing,
    method: "POST",
    headers: { Accept: "application/json" },
    body: undefined,
    cache: "no-store",
    members: ["cache", "headers", "method", "signal"],
  },
  "a change carrying nothing": {
    request: (signal: AbortSignal) => put(PATH, signal, AS_SENT),
    of: put,
    method: "PUT",
    headers: { Accept: "application/json" },
    body: undefined,
    cache: "no-store",
    members: ["cache", "headers", "method", "signal"],
  },
  "a removal": {
    request: (signal: AbortSignal) => remove(PATH, signal, AS_SENT),
    of: remove,
    method: "DELETE",
    headers: { Accept: "application/json" },
    body: undefined,
    cache: "no-store",
    members: ["cache", "headers", "method", "signal"],
  },
} satisfies Record<RequestKind, Outgoing>;

describe("every request", () => {
  /**
   * The table above is only as good as the set it covers: a function added to
   * the module and not to it could ask with whatever it liked — a header naming
   * who is asking, a credentials mode — under a suite that stayed green. Closed
   * against the module's own exports, because that is the set that ships.
   */
  it("leaves nothing this module publishes out of the table above", () => {
    const exercised = new Set<unknown>(
      Object.values(REQUESTS).map((each) => each.of),
    );

    expect(
      Object.entries(published)
        .filter(([, exported]) => !exercised.has(exported))
        .map(([name]) => name),
    ).toEqual([]);
  });

  it.each(Object.entries(REQUESTS))(
    "says only what %s has to say, and nothing of who is asking",
    async (_case, outgoing) => {
      const sent = replying(document("{}", 200));

      await outgoing.request(new AbortController().signal);

      const init = initOf(sent);
      expect(init.method).toBe(outgoing.method);
      expect(init.headers).toEqual(outgoing.headers);
      expect(init.body).toBe(outgoing.body);
      expect(init.cache).toBe(outgoing.cache);
      // `credentials` is among what must be absent: a request is `same-origin`
      // already, and naming the member can only move it off that.
      expect(Object.keys(init).toSorted()).toEqual(outgoing.members);
    },
  );

  it.each(Object.entries(REQUESTS))(
    "hands the caller's signal to %s, so it can be abandoned",
    async (_case, outgoing) => {
      const sent = replying(document("{}", 200));
      const controller = new AbortController();

      await outgoing.request(controller.signal);

      expect(initOf(sent).signal).toBe(controller.signal);
    },
  );
});
