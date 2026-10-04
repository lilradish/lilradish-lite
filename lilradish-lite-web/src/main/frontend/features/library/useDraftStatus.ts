import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useState,
} from "react";

interface PartStatus {
  readonly unsaved: boolean;
  readonly busy: boolean;
}

type ReportStatus = (part: string, status: PartStatus | null) => void;

export const DraftStatusContext = createContext<ReportStatus | null>(null);

export function useDraftStatus(versionId: string | null, expected: number) {
  const [reported, setReported] = useState<{
    readonly versionId: string | null;
    readonly parts: ReadonlyMap<string, PartStatus>;
  }>({ versionId, parts: new Map() });
  if (reported.versionId !== versionId) {
    setReported({ versionId, parts: new Map() });
  }
  const report = useCallback<ReportStatus>(
    (part, status) => {
      setReported((current) => {
        if (current.versionId !== versionId) {
          return current;
        }
        const parts = new Map(current.parts);
        const previous = parts.get(part);
        if (status === null) {
          if (!parts.delete(part)) {
            return current;
          }
        } else {
          if (
            previous?.unsaved === status.unsaved &&
            previous.busy === status.busy
          ) {
            return current;
          }
          parts.set(part, status);
        }
        return { versionId, parts };
      });
    },
    [versionId],
  );
  const parts = reported.versionId === versionId ? reported.parts : null;
  const blocked =
    parts === null ||
    parts.size < expected ||
    [...parts.values()].some((part) => part.unsaved || part.busy);
  return { report, blocked };
}

export function useDraftPartStatus(
  part: string,
  unsaved: boolean,
  busy: boolean,
) {
  const report = useContext(DraftStatusContext);
  useLayoutEffect(() => {
    report?.(part, { unsaved, busy });
    return () => report?.(part, null);
  }, [report, part, unsaved, busy]);
}
