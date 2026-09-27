import { vi } from "vitest";

import { RequestFailed } from "../api/problem";

/**
 * The server, as `fetch` meets it: each request answered by the next reply in
 * turn, the last repeated once they run out. A real `Response` built afresh
 * for each, because lib/request/http.ts reads `ok`, `status` and the body, and a body
 * is readable once.
 *
 * It sits in the source tree because the specs do: this project has no
 * separate test root to put it in. Only a spec may import it — it is kept out
 * of the coverage report and no build input names it, and a production module
 * importing it would quietly undo both.
 */
export function answering(...replies: readonly (readonly [string, number])[]) {
  let next = 0;
  const sent = vi.fn<typeof fetch>().mockImplementation(() => {
    const [body, status] = replies[Math.min(next, replies.length - 1)]!;
    next += 1;
    return Promise.resolve(responseOf(body, status));
  });
  vi.stubGlobal("fetch", sent);
  return sent;
}

/** A reply, or one the spec settles when it chooses. An empty body is sent as none. */
export type Reply =
  readonly [string, number] | Promise<readonly [string, number]>;

/**
 * The server, as `fetch` meets it, answering each request by its method and
 * path — `"GET /api/standing"` — whatever its query says. Each route answers
 * with its next reply in turn, the last repeated once they run out; a route
 * nobody declared is answered as the server answers an address naming nothing.
 *
 * A request abandoned through its signal rejects with the signal's reason, as
 * `fetch` does, whether it was abandoned before it was sent or while its reply
 * was still held.
 *
 * Only a spec may import it, for the reason `answering` gives.
 */
export function serving(routes: Readonly<Record<string, readonly Reply[]>>) {
  const answered = new Map<string, number>();
  const sent = vi.fn<typeof fetch>().mockImplementation((input, init) => {
    const signal = init?.signal ?? undefined;
    if (signal?.aborted === true) {
      return Promise.reject(signal.reason);
    }
    const route = `${init?.method ?? "GET"} ${new URL(String(input), "http://reader.test").pathname}`;
    const replies = routes[route];
    if (replies === undefined) {
      return Promise.resolve(responseOf('{"code":"NOT_FOUND"}', 404));
    }
    const next = answered.get(route) ?? 0;
    answered.set(route, next + 1);
    const reply = Promise.resolve(
      replies[Math.min(next, replies.length - 1)]!,
    ).then(([body, status]) => responseOf(body, status));
    if (signal === undefined) {
      return reply;
    }
    return new Promise<Response>((settle, refuse) => {
      signal.addEventListener("abort", () => refuse(signal.reason), {
        once: true,
      });
      reply.then(settle, refuse);
    });
  });
  vi.stubGlobal("fetch", sent);
  return sent;
}

/** Every request `serving` was sent, as `"METHOD path?query"`, in the order sent. */
export function requestsTo(sent: ReturnType<typeof serving>): string[] {
  return sent.mock.calls.map(([input, init]) => {
    const address = new URL(String(input), "http://reader.test");
    return `${init?.method ?? "GET"} ${address.pathname}${address.search}`;
  });
}

function responseOf(body: string, status: number): Response {
  return new Response(body === "" ? null : body, {
    status,
    headers: {
      "Content-Type":
        status < 300 ? "application/json" : "application/problem+json",
    },
  });
}

/** The refusal a read ended in, failing the spec where it settled instead. */
export async function refusalOf(
  pending: Promise<unknown>,
): Promise<RequestFailed> {
  try {
    await pending;
  } catch (failure) {
    if (failure instanceof RequestFailed) {
      return failure;
    }
    throw failure;
  }
  throw new Error("the read settled where it should have been refused");
}
