import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import { useTheme } from "@mui/material/styles";
import { useId, useLayoutEffect, useReducer, useRef, useState } from "react";
import { TOKENS } from "../theme/tokens";

import { markInstruction, marksIn } from "./placeholders";
import { proseRefused } from "./legibility";
import { HiddenShown, HiddenShownAcross } from "./HiddenShown";

type Input = { readonly name: string; readonly label?: string };

/** References are marked as names, while the text kept and sent remains exactly what the author wrote. */
export function InstructionText({
  text,
  takes,
  editing,
}: {
  readonly text: string;
  readonly takes: readonly Input[];
  readonly editing?: {
    readonly label: string;
    readonly readOnly: boolean;
    readonly invalid: boolean;
    readonly describedBy?: string;
    readonly change: (text: string) => void;
  };
}) {
  const editor = useRef<HTMLDivElement>(null);
  const theme = useTheme();
  const composing = useRef(false);
  const [, repaint] = useReducer((revision: number) => revision + 1, 0);
  const caret = useRef<{ start: number; end: number } | null>(null);
  const [at, setAt] = useState(text.length);
  const [choice, setChoice] = useState(0);
  const [dismissed, setDismissed] = useState(false);
  const listId = useId();
  // Refused prose remains editable in full, outside the finite Markdown marker alphabet.
  const plain = editing !== undefined && proseRefused(text) !== null;
  const marked = plain ? { source: text, segments: [] } : markInstruction(text);
  const pieces = plain ? [text] : marksIn(marked.source);
  const named = new Map(takes.map((input) => [input.name, input]));
  const start = text.lastIndexOf("{{", Math.max(0, at - 1));
  const prefix = start < 0 ? null : text.slice(start + 2, at);
  const offering =
    editing !== undefined &&
    !editing.readOnly &&
    !dismissed &&
    start >= 0 &&
    text[start - 1] !== "\\" &&
    at >= start + 2 &&
    prefix !== null &&
    !prefix.includes("}") &&
    /^[a-z0-9_]*$/.test(prefix);
  const choices = offering
    ? takes.filter((input) => input.name.startsWith(prefix!))
    : [];

  useLayoutEffect(() => {
    const root = editor.current;
    if (root === null || composing.current) {
      return;
    }
    const position = caret.current ?? selectionIn(root);
    const children = pieces.map((piece) => {
      if (typeof piece === "string") {
        return document.createTextNode(piece);
      }
      const segment = marked.segments[piece]!;
      const span = document.createElement("span");
      span.dataset.written = segment.written;
      span.contentEditable = "false";
      if ("name" in segment) {
        const input = named.get(segment.name);
        span.textContent =
          input === undefined ? segment.written : displayed(input);
        if (input?.label !== undefined && input.label !== input.name) {
          const separator = document.createElement("span");
          separator.textContent = "·";
          separator.style.color = theme.palette.ink.secondary;
          separator.style.fontWeight = String(
            TOKENS.type.roles.body.fontWeight,
          );
          span.replaceChildren(
            document.createTextNode(input.label + " "),
            separator,
            document.createTextNode(" "),
          );
          const binding = document.createElement("span");
          binding.textContent = `{{${input.name}}}`;
          binding.style.color = theme.palette.text.secondary;
          binding.style.fontFamily =
            theme.typography.identifier.fontFamily ?? "inherit";
          span.append(binding);
        }
        span.style.backgroundColor =
          input === undefined
            ? theme.palette.error.main
            : theme.palette.action.selected;
        span.style.color =
          input === undefined ? theme.palette.error.contrastText : "inherit";
        span.style.borderRadius = "3px";
        span.dataset.reference = segment.name;
      } else {
        span.textContent = segment.text;
      }
      return span;
    });
    root.replaceChildren(...children);
    if (position !== null && document.activeElement === root) {
      selectIn(root, position.start, position.end);
    }
    caret.current = null;
  });

  if (editing === undefined) {
    const shown = pieces.map((piece) => {
      if (typeof piece === "string") return piece;
      const segment = marked.segments[piece]!;
      if (!("name" in segment)) return segment.text;
      const input = named.get(segment.name);
      return input === undefined ? segment.written : displayed(input);
    });
    return (
      <HiddenShownAcross value={shown}>
        <Box
          component="span"
          dir="auto"
          sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
        >
          {pieces.map((piece, index) => {
            if (typeof piece === "string") {
              return <HiddenShown key={index} text={shown[index]!} />;
            }
            const segment = marked.segments[piece]!;
            if (!("name" in segment)) {
              return <HiddenShown key={index} text={shown[index]!} />;
            }
            const input = named.get(segment.name);
            return (
              <Box
                component="span"
                key={index}
                sx={{
                  bgcolor:
                    input === undefined ? "error.main" : "action.selected",
                  color: input === undefined ? "error.contrastText" : "inherit",
                  borderRadius: 0.5,
                  px: 0.5,
                }}
              >
                {input?.label !== undefined && input.label !== input.name ? (
                  <>
                    <HiddenShown
                      text={shown[index]!}
                      ending={`{{${input.name}}}`}
                      part="before-ending"
                    />
                    <Box
                      component="span"
                      sx={{ typography: "identifier", color: "text.secondary" }}
                    >
                      <HiddenShown
                        text={shown[index]!}
                        ending={`{{${input.name}}}`}
                        part="ending"
                      />
                    </Box>
                  </>
                ) : (
                  <HiddenShown text={shown[index]!} />
                )}
              </Box>
            );
          })}
        </Box>
      </HiddenShownAcross>
    );
  }

  const changed = () => {
    const root = editor.current!;
    const position = selectionIn(root);
    caret.current = position;
    setAt(position?.end ?? writtenIn(root).length);
    setChoice(0);
    setDismissed(false);
    editing.change(writtenIn(root));
  };
  const offer = (input: Input) => {
    const next = text.slice(0, start) + `{{${input.name}}}` + text.slice(at);
    const end = start + input.name.length + 4;
    caret.current = { start: end, end };
    setAt(end);
    setDismissed(true);
    editor.current!.focus();
    editing.change(next);
  };
  return (
    <>
      <Box
        component="div"
        ref={editor}
        role="textbox"
        aria-label={editing.label}
        aria-multiline="true"
        aria-readonly={editing.readOnly}
        aria-invalid={editing.invalid}
        aria-describedby={editing.describedBy}
        aria-autocomplete="list"
        aria-controls={choices.length === 0 ? undefined : listId}
        aria-activedescendant={
          choices.length === 0
            ? undefined
            : `${listId}-${Math.min(choice, choices.length - 1)}`
        }
        contentEditable={editing.readOnly ? false : "plaintext-only"}
        dir="auto"
        sx={{
          border: 1,
          borderColor: editing.invalid ? "error.main" : "divider",
          borderRadius: 1,
          p: 1.5,
          minHeight: "6em",
          whiteSpace: "pre-wrap",
          overflowWrap: "anywhere",
        }}
        onInput={() => {
          if (!editing.readOnly) changed();
        }}
        onCompositionStart={() => {
          composing.current = true;
        }}
        onCompositionEnd={() => {
          composing.current = false;
          changed();
          repaint();
        }}
        onKeyUp={() => {
          const position = selectionIn(editor.current!);
          if (position !== null) setAt(position.end);
        }}
        onMouseUp={() => {
          const position = selectionIn(editor.current!);
          if (position !== null) setAt(position.end);
        }}
        onBlur={() => setDismissed(true)}
        onKeyDown={(event) => {
          if (composing.current || editing.readOnly || choices.length === 0)
            return;
          if (event.key === "Escape") {
            event.preventDefault();
            setDismissed(true);
          } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            setChoice(
              (current) =>
                (current +
                  (event.key === "ArrowDown" ? 1 : choices.length - 1)) %
                choices.length,
            );
          } else if (event.key === "Enter") {
            event.preventDefault();
            offer(choices[Math.min(choice, choices.length - 1)]!);
          }
        }}
        onPaste={(event) => {
          event.preventDefault();
          if (editing.readOnly) return;
          const root = editor.current!;
          const position = selectionIn(root);
          if (position === null) return;
          const plain = event.clipboardData.getData("text/plain");
          const next = writtenIn(root);
          const end = position.start + plain.length;
          caret.current = { start: end, end };
          setAt(end);
          setChoice(0);
          setDismissed(false);
          editing.change(
            next.slice(0, position.start) + plain + next.slice(position.end),
          );
        }}
        onCopy={(event) => {
          const root = editor.current!;
          const position = selectionIn(root);
          if (position === null) return;
          event.preventDefault();
          event.clipboardData.setData(
            "text/plain",
            writtenIn(root).slice(position.start, position.end),
          );
        }}
        onCut={(event) => {
          const root = editor.current!;
          const position = selectionIn(root);
          if (position === null) return;
          event.preventDefault();
          const written = writtenIn(root);
          event.clipboardData.setData(
            "text/plain",
            written.slice(position.start, position.end),
          );
          if (editing.readOnly) return;
          caret.current = { start: position.start, end: position.start };
          setAt(position.start);
          setDismissed(false);
          editing.change(
            written.slice(0, position.start) + written.slice(position.end),
          );
        }}
      />
      {choices.length === 0 ? null : (
        <Box role="listbox" id={listId} aria-label={editing.label}>
          {choices.map((input, index) => (
            <Button
              key={input.name}
              role="option"
              tabIndex={-1}
              id={`${listId}-${index}`}
              aria-selected={index === choice}
              sx={{ minWidth: 0 }}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => offer(input)}
            >
              {input.label === undefined || input.label === input.name ? (
                displayed(input)
              ) : (
                <>
                  {input.label}{" "}
                  <Box
                    component="span"
                    sx={{
                      color: "ink.secondary",
                      fontWeight: TOKENS.type.roles.body.fontWeight,
                    }}
                  >
                    {"·"}
                  </Box>{" "}
                  <Box
                    component="span"
                    sx={{ typography: "identifier", color: "text.secondary" }}
                  >{`{{${input.name}}}`}</Box>
                </>
              )}
            </Button>
          ))}
        </Box>
      )}
    </>
  );
}

function displayed(input: Input): string {
  return input.label === undefined || input.label === input.name
    ? `{{${input.name}}}`
    : `${input.label} · {{${input.name}}}`;
}

/** Browser editing can introduce line elements; marks contribute their written text, never their displayed labels. */
function writtenIn(root: Node): string {
  let text = "";
  for (const child of root.childNodes) {
    if (child.nodeType === Node.TEXT_NODE) {
      text += child.textContent ?? "";
    } else if (child instanceof HTMLElement) {
      if (child.dataset.written !== undefined) {
        text += child.dataset.written;
      } else if (child.tagName === "BR") {
        text += "\n";
      } else {
        if (
          (child.tagName === "DIV" || child.tagName === "P") &&
          text !== "" &&
          !text.endsWith("\n")
        )
          text += "\n";
        text += writtenIn(child);
      }
    }
  }
  return text;
}

function selectionIn(root: HTMLElement): { start: number; end: number } | null {
  const selection = window.getSelection();
  if (selection === null || selection.rangeCount === 0) return null;
  const selected = selection.getRangeAt(0);
  if (
    !root.contains(selected.startContainer) ||
    !root.contains(selected.endContainer)
  )
    return null;
  const before = document.createRange();
  before.selectNodeContents(root);
  before.setEnd(selected.startContainer, selected.startOffset);
  const start = writtenIn(before.cloneContents()).length;
  before.setEnd(selected.endContainer, selected.endOffset);
  return { start, end: writtenIn(before.cloneContents()).length };
}

function selectIn(root: HTMLElement, start: number, end: number): void {
  const point = (wanted: number): [Node, number] => {
    let left = wanted;
    for (const [index, child] of Array.from(root.childNodes).entries()) {
      const length =
        child instanceof HTMLElement && child.dataset.written !== undefined
          ? child.dataset.written.length
          : (child.textContent ?? "").length;
      if (left <= length) {
        return child.nodeType === Node.TEXT_NODE
          ? [child, left]
          : [root, index + (left === 0 ? 0 : 1)];
      }
      left -= length;
    }
    return [root, root.childNodes.length];
  };
  const range = document.createRange();
  range.setStart(...point(start));
  range.setEnd(...point(end));
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
}
