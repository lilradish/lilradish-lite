import {
  NOT_A_PROBLEM_DOCUMENT,
  NOT_REACHED,
  RequestFailed,
  requestFailed,
} from "../../api/problem";

const JSON_MEDIA_TYPE = "application/json";

const NO_CONTENT = 204;

/**
 * How this side asks the server for something, so a refusal becomes a problem
 * document in one place instead of at every call site.
 *
 * No header naming who is asking is written here: the credential reaches the
 * server without this code writing it, and a header written here would be a
 * second one to be believed. Nor is anything written to say where a change
 * came from: the browser says which site made it, in `Sec-Fetch-Site` or else
 * in `Origin`, and the server judges it by the first of those it is sent.
 *
 * `credentials` is never named either. A request's credentials mode is
 * `same-origin` unless stated otherwise, and the constructor overrides it only
 * where the member exists, so stating it can only move this off what is already
 * right (WHATWG Fetch).
 */
export function get<T>(
  path: string,
  signal: AbortSignal | undefined,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, { signal }, read);
}

export function post<T>(
  path: string,
  sent: unknown,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, withDocument("POST", sent, signal), read);
}

/** The body's media type is left to the browser. */
export function postForm<T>(
  path: string,
  body: FormData,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, { method: "POST", body, signal }, read);
}

/** Something made where the address alone says what, so nothing is sent with it. */
export function postNothing<T>(
  path: string,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, { method: "POST", signal }, read);
}

/** A document naming what changes in what the address names, and nothing else. */
export function patch<T>(
  path: string,
  sent: unknown,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, withDocument("PATCH", sent, signal), read);
}

export function put<T>(
  path: string,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, { method: "PUT", signal }, read);
}

/** A document standing whole in place of what the address holds. */
export function putDocument<T>(
  path: string,
  sent: unknown,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, withDocument("PUT", sent, signal), read);
}

/** A form standing whole in place of what the address holds. */
export function putForm<T>(
  path: string,
  body: FormData,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, { method: "PUT", body, signal }, read);
}

/** `DELETE`, under a name the language leaves free. */
export function remove<T>(
  path: string,
  signal: AbortSignal,
  read: (body: unknown) => T | null,
): Promise<T> {
  return exchange(path, { method: "DELETE", signal }, read);
}

function withDocument(
  method: string,
  sent: unknown,
  signal: AbortSignal,
): RequestInit {
  return {
    method,
    // Replaces the default headers whole, so `Accept` is said again. The media
    // type goes with the body alone: without one it has nothing to describe.
    headers: { Accept: JSON_MEDIA_TYPE, "Content-Type": JSON_MEDIA_TYPE },
    body: JSON.stringify(sent),
    signal,
  };
}

/**
 * Answers are caller-specific: `no-store` bypasses the browser's cache, and the
 * server's matching directive excludes intervening caches. A withdrawn grant
 * must take hold on the next read.
 *
 * No content reaches the reader as `undefined`; only an endpoint promising it
 * should accept it.
 *
 * Transport failures become `RequestFailed`; other failures are reported as code faults.
 */
async function exchange<T>(
  path: string,
  init: RequestInit,
  read: (body: unknown) => T | null,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      cache: "no-store",
      headers: { Accept: JSON_MEDIA_TYPE },
      ...init,
    });
  } catch (unsent) {
    throw new RequestFailed({ code: NOT_REACHED }, { cause: unsent });
  }
  if (!response.ok) {
    throw await requestFailed(response);
  }
  const body =
    response.status === NO_CONTENT ? undefined : await documentOf(response);
  // Refused here rather than by the reader, which is not handed the status to refuse it under.
  const shaped = read(body);
  if (shaped === null) {
    throw new RequestFailed({
      status: response.status,
      code: NOT_A_PROBLEM_DOCUMENT,
    });
  }
  return shaped;
}

/**
 * The document a body carries, or a refusal where what it carries cannot be
 * read as one.
 *
 * The status is kept even when a body cannot be read: without it, a response
 * could be mistaken downstream for a request that never arrived.
 */
async function documentOf(response: Response): Promise<unknown> {
  try {
    return JSON.parse(await response.text()) as unknown;
  } catch (unreadable) {
    throw new RequestFailed(
      { status: response.status, code: NOT_A_PROBLEM_DOCUMENT },
      { cause: unreadable },
    );
  }
}
