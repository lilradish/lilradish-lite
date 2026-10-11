import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useRef,
  useState,
  type RefObject,
} from "react";

interface PartStatus {
  readonly unsaved: boolean;
  readonly busy: boolean;
  readonly saving: boolean;
  readonly unit: string;
  readonly node?: RefObject<HTMLElement | null>;
  readonly locate: () => void;
}

type ReportStatus = (part: string, status: PartStatus | null) => void;

export const DraftStatusContext = createContext<ReportStatus | null>(null);

export const TYPING = 'textarea, [role="textbox"]';

const CONTROL = `${TYPING}, input:not([type="hidden"]), [role="combobox"], button, [tabindex="-1"]`;

export function locatePart(target: HTMLElement) {
  target.scrollIntoView?.({ block: "nearest" });
  (target.querySelector<HTMLElement>(CONTROL) ?? target).focus();
}

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
            previous.busy === status.busy &&
            previous.saving === status.saving &&
            previous.unit === status.unit &&
            previous.node === status.node &&
            previous.locate === status.locate
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
  const values = parts === null ? [] : [...parts.values()];
  // Not a floor: a unit the page does not count holds it as surely as one it
  // counts and is missing, so a stale count is never quietly wrong.
  const blocked =
    parts === null ||
    new Set(values.map((part) => part.unit)).size !== expected ||
    values.some((part) => part.unsaved || part.busy);
  const unsavedCount = new Set(
    values.filter((part) => part.unsaved).map((part) => part.unit),
  ).size;
  const saving = values.some((part) => part.saving);
  const locateFirst = () => {
    const pending = values.filter((part) =>
      unsavedCount === 0 ? part.saving : part.unsaved,
    );
    pending.sort((first, second) => {
      const a = first.node?.current;
      const b = second.node?.current;
      if (a === undefined || a === null || b === undefined || b === null)
        return 0;
      if (a.isConnected !== b.isConnected) return a.isConnected ? -1 : 1;
      const order = a.compareDocumentPosition(b);
      return order & Node.DOCUMENT_POSITION_FOLLOWING
        ? -1
        : order & Node.DOCUMENT_POSITION_PRECEDING
          ? 1
          : 0;
    });
    pending[0]?.locate();
  };
  return { report, blocked, unsavedCount, saving, locateFirst };
}

export function useDraftPartStatus(
  part: string,
  unsaved: boolean,
  busy: boolean,
  {
    saving = false,
    node,
    locate,
    unit = part,
  }: {
    readonly saving?: boolean;
    readonly node?: RefObject<HTMLElement | null>;
    readonly locate?: () => void;
    readonly unit?: string;
  } = {},
) {
  const report = useContext(DraftStatusContext);
  const latest = useRef(locate);
  const nodeRef = useRef(node);
  useLayoutEffect(() => {
    latest.current = locate;
    nodeRef.current = node;
  });
  const find = useCallback(() => {
    if (latest.current !== undefined) latest.current();
    else {
      const target = nodeRef.current?.current;
      if (target !== undefined && target !== null) locatePart(target);
    }
  }, []);
  useLayoutEffect(() => {
    report?.(part, { unsaved, busy, saving, unit, node, locate: find });
    return () => report?.(part, null);
  }, [report, part, unsaved, busy, saving, unit, node, find]);
}
