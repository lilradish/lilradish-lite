import { useCallback, useRef, useState } from "react";

import { useResource, type Resource } from "../request/useResource";

/**
 * The row the address picks, read by `read` once each time it is picked, and
 * replaced by whatever a change to it answers until it is read again. With
 * nothing picked the last one stays as it was, so a panel on its way out does
 * not empty before it has gone.
 *
 * A row handed over as a seed is what a change answered with, and stands in for
 * the read of it once: at the pick of it that follows, or at once where it is
 * the one picked. Any other pick lets it go.
 *
 * `read` and `keyOf` must be stable, for the reason `useResource` gives: a new
 * `read` is a read of the row again.
 */
export function usePickedRow<T>(
  picked: string | undefined,
  read: (key: string, signal: AbortSignal) => Promise<T>,
  keyOf: (row: T) => string,
) {
  const seeded = useRef<T | null>(null);
  const load = useCallback(
    (signal: AbortSignal): Promise<T | null> => {
      if (picked === undefined) {
        return Promise.resolve(null);
      }
      const seed = seeded.current;
      if (seed === null || keyOf(seed) !== picked) {
        seeded.current = null;
        return read(picked, signal);
      }
      // Let go once settled rather than at once: a read the effect abandons
      // and starts again for the same pick has to find it still there.
      return Promise.resolve(seed).finally(() => {
        if (seeded.current === seed) {
          seeded.current = null;
        }
      });
    },
    [picked, read, keyOf],
  );
  const reading = useResource<T | null>(load, null);
  const [shown, setShown] = useState(reading.value);
  const [readShown, setReadShown] = useState(reading.value);
  const fresh = picked !== undefined && reading.value !== readShown;
  if (fresh) {
    setReadShown(reading.value);
    setShown(reading.value);
  }
  const value = fresh ? reading.value : shown;
  const current: Resource<T | null> =
    picked === undefined
      ? { value, problem: null, loading: false, reload: reading.reload }
      : { ...reading, value };
  return {
    read: current,
    answered: setShown,
    reread: reading.reload,
    seed(row: T) {
      seeded.current = row;
      if (keyOf(row) === picked) {
        reading.reload();
      }
    },
  };
}
