import { get } from "./http";
import { pageFrom, type Page } from "./page";

/** The caller names any other query; the cursor belongs to the page being asked for. */
export function cursorLoader<T>(
  path: string,
  rowFrom: (row: unknown) => T | null,
  query?: URLSearchParams,
): (cursor: string | null, signal: AbortSignal) => Promise<Page<T>> {
  return (cursor, signal) => {
    const parameters = new URLSearchParams(query);
    parameters.delete("cursor");
    if (cursor !== null) {
      parameters.set("cursor", cursor);
    }
    const search = parameters.toString();
    return get(search === "" ? path : `${path}?${search}`, signal, (body) =>
      pageFrom(body, rowFrom),
    );
  };
}
