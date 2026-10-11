import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import CheckIcon from "@mui/icons-material/Check";
import CloseIcon from "@mui/icons-material/Close";
import { useEffect, useId, useRef, type ReactNode } from "react";

import { say } from "../../i18n/app";
import type { Action } from "../request/useAction";
import { TOKENS } from "../theme/tokens";
import type { Reason } from "./Press";

const EDGE = "1px";
const [paddingTop, paddingEnd, paddingBottom, paddingStart] =
  TOKENS.inPlace.padding.split(" ");

export const INLINE_TEXT_END_WIDTH = `calc(2 * ${TOKENS.inPlace.control} + ${TOKENS.inPlace.controlGap} + ${TOKENS.inPlace.textGap})`;
export const INLINE_OUTSET_WIDTH = `calc(${paddingStart} + ${paddingEnd} + 2 * ${EDGE})`;

export function inlineEditWidth(
  characters: number,
  available = "100%",
): string {
  return `min(${available}, calc(${characters}ch + ${INLINE_TEXT_END_WIDTH} + ${INLINE_OUTSET_WIDTH}))`;
}

export const INLINE_SLOT_SX = {
  minWidth: 0,
  font: "inherit",
  color: "inherit",
  marginBlockStart: `calc(-1 * (${paddingTop} + ${EDGE}))`,
  marginBlockEnd: `calc(-1 * (${paddingBottom} + ${EDGE}))`,
  marginInlineStart: `calc(-1 * (${paddingStart} + ${EDGE}))`,
  marginInlineEnd: `calc(-1 * (${paddingEnd} + ${EDGE}))`,
} as const;

export const INLINE_BOX_SX = {
  display: "grid",
  gridTemplateColumns: `minmax(0, 1fr) calc(2 * ${TOKENS.inPlace.control} + ${TOKENS.inPlace.controlGap})`,
  columnGap: TOKENS.inPlace.textGap,
  alignItems: "end",
  boxSizing: "border-box",
  padding: TOKENS.inPlace.padding,
  borderWidth: EDGE,
  borderStyle: "solid",
  borderRadius: TOKENS.shape.controlRadius,
} as const;

export function InlineEdit<T>({
  open,
  onShut,
  action,
  act,
  reason,
  underway,
  refusal,
  children,
  unchanged = false,
  choiceOpen = false,
  confirmAfterChange,
  readValue,
  characters,
}: {
  readonly open: boolean;
  readonly onShut: (returnFocus?: boolean) => void;
  readonly action: Action<T>;
  readonly act: (signal: AbortSignal) => Promise<T>;
  readonly reason?: Reason;
  readonly underway?: string;
  readonly refusal: ReactNode;
  readonly children: ReactNode;
  readonly unchanged?: boolean;
  readonly choiceOpen?: boolean;
  readonly confirmAfterChange?: string;
  readonly readValue?: string;
  readonly characters?: number;
}) {
  const reasonId = useId();
  const form = useRef<HTMLFormElement>(null);
  const submitting = useRef(false);
  const composing = useRef(false);
  const cancelled = useRef(false);
  const previousChoice = useRef(confirmAfterChange);
  const wasOpen = useRef(open);
  const focusInput = () => {
    form.current
      ?.querySelector<HTMLElement>(
        'input:not([aria-hidden="true"]):not([tabindex="-1"]), textarea, [role="combobox"]',
      )
      ?.focus();
  };
  const focusBelongsHere = () =>
    document.activeElement === document.body ||
    form.current?.contains(document.activeElement) === true;
  const save = (returnFocus = false) => {
    if (
      cancelled.current ||
      composing.current ||
      submitting.current ||
      action.running
    )
      return;
    if (unchanged) {
      onShut(returnFocus && focusBelongsHere());
    } else if (reason === undefined) {
      submitting.current = true;
      action.run(async (signal) => {
        try {
          const result = await act(signal);
          if (!signal.aborted) onShut(returnFocus && focusBelongsHere());
          return result;
        } catch (failure) {
          queueMicrotask(focusInput);
          throw failure;
        } finally {
          submitting.current = false;
        }
      });
    }
  };
  useEffect(() => {
    if (open && !wasOpen.current) cancelled.current = false;
    wasOpen.current = open;
    if (previousChoice.current !== confirmAfterChange) {
      previousChoice.current = confirmAfterChange;
      if (open && confirmAfterChange !== undefined) save(true);
    }
  });
  if (!open) return null;
  const cancel = () => {
    if (!action.running && !submitting.current) {
      cancelled.current = true;
      onShut(choiceOpen || focusBelongsHere());
    }
  };
  return (
    <Box
      ref={form}
      component="form"
      title={say("inline.keys")}
      sx={{
        ...INLINE_SLOT_SX,
        width: characters === undefined ? "100%" : inlineEditWidth(characters),
      }}
      onCompositionStart={() => {
        composing.current = true;
      }}
      onCompositionEnd={() => {
        composing.current = false;
      }}
      onBlur={(event) => {
        if (composing.current || choiceOpen) return;
        if (
          event.relatedTarget instanceof Node &&
          event.currentTarget.contains(event.relatedTarget)
        )
          return;
        if (
          event.relatedTarget instanceof Element &&
          event.relatedTarget.closest('[role="listbox"], [role="option"]')
        )
          return;
        // A portal or a cancel click can move focus before its own event runs.
        queueMicrotask(() => {
          if (!choiceOpen && !form.current?.contains(document.activeElement))
            save();
        });
      }}
      onSubmit={(event) => {
        event.preventDefault();
        save(true);
      }}
      onKeyDownCapture={(event) => {
        if (
          event.defaultPrevented ||
          event.nativeEvent.isComposing ||
          composing.current
        )
          return;
        if (event.key === "Escape") {
          event.preventDefault();
          cancel();
        } else if (
          event.key === "Enter" &&
          event.target instanceof HTMLTextAreaElement
        ) {
          event.preventDefault();
          save(true);
        }
      }}
    >
      <Box
        sx={{
          ...INLINE_BOX_SX,
          position: "relative",
          borderColor: "act.main",
          "& .MuiFormControl-root, & .MuiStack-root": {
            font: "inherit",
            color: "inherit",
          },
          "& .MuiOutlinedInput-root": {
            font: "inherit",
            color: "inherit",
            padding: 0,
            border: 0,
            outline: "none",
            boxShadow: "none",
          },
          "&&& .MuiOutlinedInput-notchedOutline": {
            border: 0,
            borderWidth: 0,
            borderStyle: "none",
            padding: 0,
            outline: "none",
          },
          "& .MuiOutlinedInput-input": {
            padding: 0,
            font: "inherit",
            color: "inherit",
            height: "auto",
            boxSizing: "border-box",
            border: 0,
            outline: "none",
            boxShadow: "none",
          },
          "& .MuiSelect-select": { paddingInlineEnd: "24px !important" },
          "& .MuiSelect-icon": { right: "auto", insetInlineEnd: 0 },
        }}
      >
        <Box
          sx={{
            flex: "1 1 auto",
            minWidth: 0,
            maxWidth: "var(--inline-text-measure, 100%)",
          }}
        >
          {children}
        </Box>
        <Box
          sx={{
            display: "flex",
            flexShrink: 0,
            gap: TOKENS.inPlace.controlGap,
            zIndex: 2,
          }}
        >
          <Tooltip title={reason?.words ?? say("inline.save")}>
            <span>
              <IconButton
                type="submit"
                aria-label={say("inline.save")}
                aria-describedby={reason === undefined ? undefined : reasonId}
                disabled={action.running || reason !== undefined}
                sx={{
                  width: TOKENS.inPlace.control,
                  height: TOKENS.inPlace.control,
                  p: 0,
                  border: 0,
                  borderRadius: TOKENS.shape.chipRadius,
                  color: "ink.secondary",
                  bgcolor: "transparent",
                  "&:hover, &:active": {
                    bgcolor: "act.hover",
                    color: "ink.secondary",
                  },
                  "& .MuiSvgIcon-root": {
                    width: TOKENS.inPlace.icon,
                    height: TOKENS.inPlace.icon,
                  },
                }}
              >
                <CheckIcon />
              </IconButton>
            </span>
          </Tooltip>
          <IconButton
            aria-label={say("inline.cancel")}
            aria-describedby={
              action.running && underway !== undefined ? reasonId : undefined
            }
            disabled={action.running}
            onPointerDown={(event) => event.preventDefault()}
            onClick={cancel}
            sx={{
              width: TOKENS.inPlace.control,
              height: TOKENS.inPlace.control,
              p: 0,
              border: 0,
              borderRadius: TOKENS.shape.chipRadius,
              color: "ink.secondary",
              bgcolor: "transparent",
              "&:hover, &:active": {
                bgcolor: "act.hover",
                color: "ink.secondary",
              },
              "& .MuiSvgIcon-root": {
                width: TOKENS.inPlace.icon,
                height: TOKENS.inPlace.icon,
              },
            }}
          >
            <CloseIcon />
          </IconButton>
        </Box>
      </Box>
      {readValue === undefined || reason === undefined || unchanged ? null : (
        <Typography
          aria-hidden="true"
          variant="caption"
          component="div"
          sx={{
            visibility: "hidden",
            mx: "14px",
            mt: "3px",
            minWidth: 0,
            contain: "inline-size",
            overflowWrap: "anywhere",
          }}
        >
          {reason.words}
        </Typography>
      )}
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{
          position: "absolute",
          width: "1px",
          height: "1px",
          overflow: "hidden",
          clipPath: "inset(50%)",
        }}
      >
        {say("inline.keys")}
      </Typography>
      {!action.running || underway === undefined ? null : (
        <Typography
          id={reasonId}
          variant="caption"
          component="div"
          sx={{
            position: "absolute",
            width: "1px",
            height: "1px",
            overflow: "hidden",
            clipPath: "inset(50%)",
          }}
        >
          {underway}
        </Typography>
      )}
      {reason === undefined ||
      (action.running && underway !== undefined) ? null : (
        <Typography
          id={reasonId}
          variant="caption"
          component="div"
          sx={{
            position: "absolute",
            width: "1px",
            height: "1px",
            overflow: "hidden",
            clipPath: "inset(50%)",
            color:
              reason.severity === "warning" ? "warning.main" : "text.secondary",
          }}
        >
          {reason.words}
        </Typography>
      )}
      {refusal}
    </Box>
  );
}
