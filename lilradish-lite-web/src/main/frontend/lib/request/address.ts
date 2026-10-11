import { NOT_AN_ADDRESS, RequestFailed } from "../../api/problem";

/**
 * Escapes `id` as one segment. `.` and `..` are never sent: escaping leaves them as they are, and the
 * address would resolve away from what it names. What an id may be is the
 * server's to judge.
 */
export function atSegment<T>(
  base: string,
  id: string,
  ask: (address: string) => Promise<T>,
): Promise<T> {
  if (id === "." || id === "..") {
    return Promise.reject(new RequestFailed({ code: NOT_AN_ADDRESS }));
  }
  return ask(`${base}/${encodeURIComponent(id)}`);
}
