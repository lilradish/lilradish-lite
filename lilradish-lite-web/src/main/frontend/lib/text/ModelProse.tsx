import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { say } from "../../i18n/lib";
import { Widest } from "../action/Widest";
import { useShownAfter } from "../action/useShownAfter";
import "./modelProseMarkdown.css";
import { prepareProse, renderProseInParts } from "./proseMarkdown";
import {
  proseNeedsBackground,
  proseTooDeep,
  type PreparedProse,
} from "./prosePreparation";
import { ProseLayoutQueue } from "./ProseLayout";
import { drawingOf } from "./hidden";
import { HiddenShownAcross, textsIn } from "./HiddenShown";

const CharactersAcross = createContext<
  | {
      readonly prepared: ReturnType<typeof prepareProse> | undefined;
      readonly failed: boolean;
      readonly background: boolean;
      readonly ready: boolean;
      readonly drawing: ReturnType<typeof drawingOf>;
      readonly register: (text: string) => () => void;
    }
  | undefined
>(undefined);
const ReadingScope = createContext<{
  readonly written: boolean;
  readonly register: () => () => void;
  readonly independent: boolean;
  readonly valueCard: boolean;
} | null>(null);

/** One switch per reading; independent readings still share the outer character budget. */
export function ProseScope({
  children,
  resetKey,
  independent = false,
  valueCard = false,
  toolbar,
}: {
  readonly children: ReactNode;
  readonly resetKey?: unknown;
  readonly independent?: boolean;
  readonly valueCard?: boolean;
  readonly toolbar?: (toggle: ReactNode) => ReactNode;
}) {
  const outer = useContext(ReadingScope);
  const [reading, setReading] = useState({
    resetKey,
    written: false,
    formatted: 0,
  });
  if (reading.resetKey !== resetKey)
    setReading({ ...reading, resetKey, written: false });
  const register = useCallback(() => {
    setReading((was) => ({ ...was, formatted: was.formatted + 1 }));
    return () =>
      setReading((was) => ({
        ...was,
        formatted: was.formatted - 1,
        written: was.formatted === 1 ? false : was.written,
      }));
  }, []);
  const context = useMemo(
    () => ({
      written: reading.written,
      register,
      independent,
      valueCard: valueCard || outer?.valueCard === true,
    }),
    [reading.written, register, independent, valueCard, outer?.valueCard],
  );
  if (outer !== null && !independent) return children;
  const toggle =
    reading.formatted === 0 ? null : (
      <Button
        variant="text"
        size="small"
        color="inherit"
        sx={{ userSelect: "none" }}
        onClick={() => setReading((was) => ({ ...was, written: !was.written }))}
      >
        <Widest
          labels={[say("prose.showWritten"), say("prose.showFormatted")]}
          shown={reading.written ? 1 : 0}
        />
      </Button>
    );
  return (
    <ReadingScope.Provider value={context}>
      {toolbar === undefined ? toggle : toolbar(toggle)}
      {children}
    </ReadingScope.Provider>
  );
}

/** A separate reading must not borrow another value's prepared texts or character budget. */
export function ProseBoundary({ children }: { readonly children: ReactNode }) {
  return (
    <CharactersAcross.Provider value={undefined}>
      <ReadingScope.Provider value={null}>{children}</ReadingScope.Provider>
    </CharactersAcross.Provider>
  );
}

/** Prepares only mounted prose, with one budget including the other plain texts of its value. */
export function FormattedProseAcross({
  value,
  children,
}: {
  readonly value: unknown;
  readonly children: ReactNode;
}) {
  const outer = useContext(CharactersAcross);
  return outer === undefined ? (
    <ProseScope>
      <ProseCharacters value={value}>{children}</ProseCharacters>
    </ProseScope>
  ) : (
    children
  );
}

function ProseCharacters({
  value,
  children,
}: {
  readonly value: unknown;
  readonly children: ReactNode;
}) {
  const scope = useContext(ReadingScope);
  // Read objects are frequently reconstructed without changing their content.
  const currentTexts = textsIn(value);
  const [texts, setTexts] = useState(currentTexts);
  if (
    texts.length !== currentTexts.length ||
    texts.some((text, at) => text !== currentTexts[at])
  )
    setTexts(currentTexts);
  const [active, setActive] = useState<ReadonlyMap<symbol, string>>(new Map());
  const register = useCallback((text: string) => {
    const id = Symbol();
    setActive((was) => new Map(was).set(id, text));
    return () =>
      setActive((was) => {
        const next = new Map(was);
        next.delete(id);
        return next;
      });
  }, []);
  const selected = useMemo(() => [...active.values()], [active]);
  const raw = useMemo(() => drawingOf(texts), [texts]);
  const background = useMemo(() => proseNeedsBackground(selected), [selected]);
  const key = useMemo(() => ({ texts, selected }), [texts, selected]);
  const [finished, setFinished] = useState<
    | {
        readonly key: typeof key;
        readonly prepared?: ReturnType<typeof prepareProse>;
        readonly failed: boolean;
        readonly ready: boolean;
      }
    | undefined
  >(undefined);
  if (finished !== undefined && finished.key !== key) setFinished(undefined);
  const ordinary = useMemo(
    () =>
      background
        ? undefined
        : selected.length === 0
          ? { drawing: raw, rendered: new Map<string, ReactNode>() }
          : prepareProse(texts, selected),
    [background, texts, selected, raw],
  );
  useEffect(() => {
    if (!background) return;
    let cancelled = false;
    let worker: Worker | undefined;
    let layout: ProseLayoutQueue | undefined;
    const fail = () => {
      layout?.cancel();
      if (!cancelled) setFinished({ key, failed: true, ready: false });
      worker?.terminate();
    };
    try {
      worker = new Worker(new URL("./proseWorker.ts", import.meta.url), {
        type: "module",
      });
      worker.onmessage = (
        event: MessageEvent<{
          readonly prepared?: PreparedProse;
          readonly failed?: boolean;
        }>,
      ) => {
        if (cancelled) return;
        if (event.data.prepared !== undefined) {
          try {
            layout = new ProseLayoutQueue(() => {
              if (!cancelled)
                setFinished((was) =>
                  was?.key === key ? { ...was, ready: true } : was,
                );
            }, fail);
            setFinished({
              key,
              prepared: renderProseInParts(event.data.prepared, layout),
              failed: false,
              ready: false,
            });
          } catch {
            fail();
          }
        } else fail();
        worker?.terminate();
      };
      worker.onerror = fail;
      worker.onmessageerror = fail;
      worker.postMessage({ texts, formattedTexts: selected });
    } catch {
      fail();
    }
    return () => {
      cancelled = true;
      layout?.cancel();
      worker?.terminate();
    };
  }, [background, key, texts, selected]);
  const current = finished?.key === key ? finished : undefined;
  const prepared = background ? current?.prepared : ordinary;
  const failed = background && current?.failed === true;
  const ready = !background || current?.ready === true;
  const waiting = useMemo(() => {
    if (!background) return raw;
    const remaining = new Map<string, number>();
    for (const text of selected)
      remaining.set(text, (remaining.get(text) ?? 0) + 1);
    return drawingOf(
      texts.filter((text) => {
        const count = remaining.get(text) ?? 0;
        if (count > 0) {
          remaining.set(text, count - 1);
          return false;
        }
        return true;
      }),
    );
  }, [background, raw, texts, selected]);
  const drawing =
    scope?.written === true || failed
      ? raw
      : ready
        ? (prepared?.drawing ?? waiting)
        : waiting;
  const context = useMemo(
    () => ({ prepared, failed, background, ready, register, drawing }),
    [prepared, failed, background, ready, register, drawing],
  );
  return (
    <CharactersAcross.Provider value={context}>
      {"kept" in drawing ? (
        <Typography variant="body2">
          {say("text.leftOut", { count: drawing.leftOut })}
        </Typography>
      ) : null}
      <HiddenShownAcross value={value} asDrawing={drawing} suppressNotice>
        {children}
      </HiddenShownAcross>
    </CharactersAcross.Provider>
  );
}

const PROSE_SX = {
  "& .prose-character": {
    display: "inline-block",
    mx: 0.25,
    px: 0.25,
    border: 1,
    borderColor: "divider",
    borderRadius: 0.5,
    color: "text.secondary",
    typography: "identifier",
    whiteSpace: "nowrap",
  },
};

/** Several-line text, with its original spelling one press away. */
export function ModelProse({
  text,
  plain,
}: {
  readonly text: string;
  readonly plain: ReactNode;
}) {
  const across = useContext(CharactersAcross);
  return across === undefined ? (
    <FormattedProseAcross value={text}>
      <ProseText text={text} plain={plain} />
    </FormattedProseAcross>
  ) : (
    <ProseText text={text} plain={plain} />
  );
}

function ProseText({
  text,
  plain,
}: {
  readonly text: string;
  readonly plain: ReactNode;
}) {
  const scope = useContext(ReadingScope);
  const across = useContext(CharactersAcross)!;
  const tooDeep = useMemo(() => proseTooDeep(text), [text]);
  const registerText = across.register;
  const locallyWritten = scope?.independent === true && scope.written;
  const waiting =
    across.background &&
    !across.ready &&
    !across.failed &&
    scope?.written !== true;
  const stillReading = useShownAfter(
    waiting && scope?.valueCard === true,
    300,
    text,
  );
  useLayoutEffect(
    () => (tooDeep || locallyWritten ? undefined : registerText(text)),
    [registerText, text, tooDeep, locallyWritten],
  );
  const registerSwitch = scope?.register;
  useLayoutEffect(
    () => (tooDeep || across.failed ? undefined : registerSwitch?.()),
    [registerSwitch, tooDeep, across.failed],
  );
  if (tooDeep || across.failed)
    return (
      <>
        {plain}
        <Typography variant="body2">
          {say(tooDeep ? "prose.tooDeep" : "prose.couldNotLayOut")}
        </Typography>
      </>
    );
  const plainReady = (
    "kept" in across.drawing ? across.drawing.kept : across.drawing.pieces
  ).has(text);
  // A local switch first asks the shared preparation to include this raw spelling before drawing it.
  if (locallyWritten && !plainReady) return null;
  if (scope?.written === true && !across.background) return plain;
  return (
    <>
      {scope?.written === true ? plain : null}
      {waiting && (scope?.valueCard !== true || stillReading) ? (
        <Typography variant="body2">
          {say(
            scope?.valueCard === true ? "prose.stillReading" : "read.pending",
          )}
        </Typography>
      ) : null}
      <Box
        className="model-prose-markdown"
        sx={PROSE_SX}
        dir="auto"
        hidden={scope?.written === true || !across.ready}
      >
        {across.prepared?.rendered.get(text)}
      </Box>
    </>
  );
}
