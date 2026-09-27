import { useEffect, useRef, useState } from "react";

import { useResource } from "../../lib/request/useResource";

interface Revised {
  readonly revision: number;
}

/**
 * A version's content as read and as each write answered, whichever is newest; read again whenever the page
 * is, and afresh when a part asks, which draws the parts anew and lets go of everything unsaved in them.
 */
export function useVersionContent<T extends Revised, Part extends string>(
  load: (signal: AbortSignal) => Promise<T | null>,
  readCount: number,
  onWritten: () => void,
  onShown: (content: T | null) => void,
) {
  const read = useResource<T | null>(load, null);
  const reload = read.reload;
  const readAt = useRef(readCount);
  useEffect(() => {
    if (readAt.current !== readCount) {
      readAt.current = readCount;
      reload();
    }
  }, [readCount, reload]);
  const [shown, setShown] = useState(read.value);
  const [readShown, setReadShown] = useState(read.value);
  if (read.value !== readShown) {
    setReadShown(read.value);
    // A read sent before a write landed can answer with the revision that write replaced.
    if (!olderThan(read.value, shown)) {
      setShown(read.value);
    }
  }
  useEffect(() => {
    onShown(shown);
  }, [shown, onShown]);
  const [afresh, setAfresh] = useState<{
    readonly count: number;
    readonly from: Part | null;
  }>({ count: 0, from: null });
  const written = (answered: T) => {
    setShown((now) => (olderThan(answered, now) ? now : answered));
    onWritten();
  };
  const readAfresh = (from: Part) => () => {
    setAfresh((now) => ({ count: now.count + 1, from }));
    reload();
    onWritten();
  };
  return { read, shown, written, afresh, readAfresh };
}

function olderThan<T extends Revised>(read: T | null, shown: T | null) {
  return read !== null && shown !== null && read.revision < shown.revision;
}
