import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import SvgIcon from "@mui/material/SvgIcon";
import Typography from "@mui/material/Typography";
import HourglassEmpty from "@mui/icons-material/HourglassEmpty";
import CancelOutlined from "@mui/icons-material/CancelOutlined";
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ClipboardEvent,
  type ReactNode,
} from "react";

import { say } from "../../../i18n/app";
import { say as libSaid } from "../../../i18n/lib";
import { Widest } from "../../../lib/action/Widest";
import { useShownAfter } from "../../../lib/action/useShownAfter";
import { UNSEEN_SX, VALUE_CARD_SX } from "../../../lib/layout/parts";
import { useAction } from "../../../lib/request/useAction";
import { ProseBoundary, ProseScope } from "../../../lib/text/ModelProse";
import { StatePill } from "../../../lib/text/StatePill";

export const LONGEST_OPEN_TEXT = 200;

export function copySelection(event: ClipboardEvent) {
  if (event.defaultPrevented) return;
  const selection = window.getSelection();
  if (selection === null || selection.isCollapsed || selection.rangeCount === 0)
    return;
  const contents = selection.getRangeAt(0).cloneContents();
  contents
    .querySelectorAll("[data-copy-control], button, input, select, textarea")
    .forEach((control) => control.remove());
  contents.querySelectorAll("br").forEach((line) => line.replaceWith("\n"));
  const walker = document.createTreeWalker(contents, NodeFilter.SHOW_TEXT);
  const parts: string[] = [];
  const structuralWhitespace = new Map<Element, boolean>();
  let prior: Element | null = null;
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const parent = node.parentElement;
    // Markdown's whitespace between block nodes is layout, unlike whitespace inside a value.
    if (/^\s*$/.test(node.textContent ?? "") && parent !== null) {
      let structural = structuralWhitespace.get(parent);
      if (structural === undefined) {
        structural =
          parent.querySelector(
            ":scope > p, :scope > table, :scope > ul, :scope > ol, :scope > blockquote, :scope > pre",
          ) !== null;
        structuralWhitespace.set(parent, structural);
      }
      if (structural) continue;
    }
    if (prior !== null && parent !== null) {
      const beforeCell = prior.closest("th, td");
      const afterCell = parent.closest("th, td");
      const beforeBlock = prior.closest(
        "p, pre, blockquote, h1, h2, h3, h4, h5, h6, li",
      );
      const afterBlock = parent.closest(
        "p, pre, blockquote, h1, h2, h3, h4, h5, h6, li",
      );
      const beforeValue = prior.closest("[data-value-content]");
      const afterValue = parent.closest("[data-value-content]");
      if (
        beforeValue !== afterValue ||
        (beforeCell === null) !== (afterCell === null)
      ) {
        parts.push("\n\n");
      } else if (beforeCell !== afterCell) {
        parts.push(
          beforeCell?.closest("tr") === afterCell?.closest("tr") ? "\t" : "\n",
        );
      } else if (beforeBlock !== afterBlock) {
        parts.push(
          beforeBlock?.tagName === "LI" && afterBlock?.tagName === "LI"
            ? "\n"
            : "\n\n",
        );
      }
    }
    parts.push(node.textContent ?? "");
    prior = parent;
  }
  const text = parts.join("");
  event.clipboardData.setData("text/plain", text);
  event.preventDefault();
}

function copyWithEvent(text: string): boolean {
  let written = false;
  const copy = (event: globalThis.ClipboardEvent) => {
    try {
      if (event.clipboardData === null) return;
      event.clipboardData.setData("text/plain", text);
      event.preventDefault();
      written = true;
    } catch {
      written = false;
    }
  };
  try {
    document.addEventListener("copy", copy, { once: true });
    return document.execCommand("copy") && written;
  } catch {
    return false;
  } finally {
    document.removeEventListener("copy", copy);
  }
}

async function copyText(text: string, signal: AbortSignal): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText !== undefined) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // A refused modern copy can still use the copy event from this click.
  }
  return !signal.aborted && copyWithEvent(text);
}

export function CopyValue({
  text,
  describedBy,
  step = false,
  onCopyFailed,
}: {
  readonly text: string;
  readonly describedBy?: string;
  readonly step?: boolean;
  readonly onCopyFailed: (failed: boolean) => void;
}) {
  const [copied, setCopied] = useState<{
    readonly text: string;
    readonly number: number;
  } | null>(null);
  const copying = useAction<string | null>((written) => {
    if (written === null) {
      onCopyFailed(true);
    } else {
      setCopied((was) => ({ text: written, number: (was?.number ?? 0) + 1 }));
    }
  });
  const spinning = useShownAfter(copying.running);
  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(null), 2000);
    return () => window.clearTimeout(timer);
  }, [copied]);
  return (
    <>
      <Button
        variant="text"
        size="small"
        color="inherit"
        aria-describedby={describedBy}
        aria-disabled={copying.running || undefined}
        disableRipple={copying.running}
        data-copy-control
        sx={{
          userSelect: "none",
          position: "relative",
          ...(copying.running ? { cursor: "default" } : {}),
        }}
        onClick={
          copying.running
            ? undefined
            : () => {
                onCopyFailed(false);
                setCopied(null);
                copying.run(async (signal) =>
                  (await copyText(text, signal)) ? text : null,
                );
              }
        }
      >
        <Box
          component="span"
          sx={spinning ? { color: "transparent" } : undefined}
        >
          <Widest
            labels={[
              say(step ? "step.copy" : "value.copy"),
              say("value.copied"),
            ]}
            shown={copied?.text === text ? 1 : 0}
          />
        </Box>
        {spinning ? (
          <>
            <Box component="span" sx={UNSEEN_SX}>
              {libSaid("action.underway")}
            </Box>
            <Box
              component="span"
              aria-hidden
              sx={{
                position: "absolute",
                inset: 0,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <CircularProgress size={16} color="inherit" />
            </Box>
          </>
        ) : null}
      </Button>
    </>
  );
}

export function CopyFailure({
  failed,
  align = "left",
}: {
  readonly failed: boolean;
  readonly align?: "left" | "right";
}) {
  return (
    <Box
      role="status"
      data-copy-control
      sx={{
        typography: "supportingProse",
        color: "warning.main",
        display: "flex",
        justifyContent: align === "right" ? "flex-end" : "flex-start",
        textAlign: align,
        gap: "6px",
        ...(failed ? { mt: "4px" } : {}),
      }}
    >
      {!failed ? null : (
        <>
          <SvgIcon
            aria-hidden
            viewBox="0 0 24 24"
            sx={{ width: 16, height: 16, flexShrink: 0, mt: "2px" }}
          >
            <path
              d="M12 3 2 21h20L12 3Zm0 5v6m0 3v1"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </SvgIcon>
          <span>{libSaid("value.copyFailed")}</span>
        </>
      )}
    </Box>
  );
}

export function ValueCard({
  label,
  written,
  copyable = true,
  status,
  foot,
  children,
}: {
  readonly label: ReactNode;
  readonly written: string | undefined;
  readonly copyable?: boolean;
  readonly status?: string;
  readonly foot?: ReactNode;
  readonly children: ReactNode;
}) {
  const [copyFailed, setCopyFailed] = useState(false);
  const titleId = useId();
  const contentId = useId();
  const statusId = useId();
  const content = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);
  const [reading, setReading] = useState({ written, open: false });
  if (reading.written !== written) setReading({ written, open: false });
  const open = reading.written === written && reading.open;
  const [near, setNear] = useState(
    () => typeof IntersectionObserver === "undefined",
  );
  const [long, setLong] = useState(false);
  const chip =
    status === "waiting_on_review" ||
    status === "refused" ||
    status === "refused_for_length";
  useEffect(() => {
    const box = content.current;
    if (near || box === null || typeof IntersectionObserver === "undefined")
      return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setNear(true);
          observer.disconnect();
        }
      },
      { rootMargin: "320px" },
    );
    observer.observe(box);
    return () => observer.disconnect();
  }, [near]);
  useLayoutEffect(() => {
    const box = content.current;
    const body = inner.current;
    if (!near || box === null || body === null) return;
    const measure = () => {
      const rootSize =
        Number.parseFloat(
          getComputedStyle(document.documentElement).fontSize,
        ) || 16;
      setLong(
        body.getBoundingClientRect().height > rootSize * 12 ||
          (!open &&
            box.clientHeight > 0 &&
            box.scrollHeight > box.clientHeight),
      );
    };
    measure();
    if (typeof ResizeObserver !== "undefined") {
      const observer = new ResizeObserver(measure);
      observer.observe(body);
      observer.observe(box);
      return () => observer.disconnect();
    }
    const observer = new MutationObserver(measure);
    observer.observe(body, {
      childList: true,
      subtree: true,
      characterData: true,
    });
    window.addEventListener("resize", measure);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measure);
    };
  }, [near, open, written]);
  return (
    <Box
      component="section"
      aria-labelledby={titleId}
      aria-describedby={chip ? statusId : undefined}
      sx={VALUE_CARD_SX}
      onCopy={copySelection}
    >
      <ProseScope
        independent
        valueCard
        resetKey={written}
        toolbar={(toggle) => (
          <Box data-copy-control sx={{ mb: 1 }}>
            <Box
              sx={{
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: 1,
                userSelect: "none",
              }}
            >
              <Box
                sx={{
                  mr: "auto",
                  display: "flex",
                  flexWrap: "wrap",
                  alignItems: "center",
                  gap: 0.8,
                }}
              >
                <div role="term">
                  <Typography
                    id={titleId}
                    variant="h3"
                    sx={{ typography: "valueLabel", color: "text.secondary" }}
                  >
                    <bdi>{label}</bdi>
                  </Typography>
                </div>
                {chip ? (
                  <ValueStatusChip status={status} id={statusId} />
                ) : null}
              </Box>
              {toggle}
              {written === undefined || !copyable ? null : (
                <CopyValue
                  text={written}
                  describedBy={titleId}
                  onCopyFailed={setCopyFailed}
                />
              )}
            </Box>
            <CopyFailure failed={copyFailed} align="right" />
          </Box>
        )}
      >
        <Box
          id={contentId}
          ref={content}
          role="definition"
          aria-labelledby={titleId}
          data-value-content
          inert={long && !open ? true : undefined}
          sx={
            !open
              ? {
                  maxHeight: "12rem",
                  overflow: "hidden",
                  overflowWrap: "anywhere",
                  position: "relative",
                  ...(long
                    ? {
                        "&::after": {
                          content: '""',
                          position: "absolute",
                          inset: "auto 0 0",
                          height: "3.5rem",
                          background: (
                            theme: import("@mui/material/styles").Theme,
                          ) =>
                            `linear-gradient(transparent, ${theme.palette.background.paper})`,
                          pointerEvents: "none",
                        },
                      }
                    : {}),
                }
              : { overflowWrap: "anywhere" }
          }
        >
          <div ref={inner}>{near ? children : null}</div>
        </Box>
        {!long ? null : (
          <Button
            variant="text"
            size="small"
            color="inherit"
            data-copy-control
            sx={{ userSelect: "none" }}
            aria-expanded={open}
            aria-controls={contentId}
            aria-describedby={titleId}
            onClick={() => setReading((was) => ({ ...was, open: !was.open }))}
          >
            <Widest
              labels={[say("value.showAll"), say("value.showLess")]}
              shown={open ? 1 : 0}
            />
          </Button>
        )}
      </ProseScope>
      {foot === undefined || foot === null ? null : (
        <ProseBoundary>
          <Box
            sx={{
              mt: "10px",
              typography: "supportingProse",
              color: "text.secondary",
            }}
          >
            {foot}
          </Box>
        </ProseBoundary>
      )}
    </Box>
  );
}

export function ValueStatusChip({
  status,
  id,
}: {
  readonly status?: string;
  readonly id?: string;
}) {
  if (
    status !== "waiting_on_review" &&
    status !== "refused" &&
    status !== "refused_for_length"
  )
    return null;
  const waiting = status === "waiting_on_review";
  const rejected = status === "refused";
  return (
    <StatePill
      id={id}
      tone={waiting ? "waiting" : rejected ? "failed" : "rejectedForLength"}
      icon={
        waiting ? (
          <HourglassEmpty fontSize="inherit" />
        ) : rejected ? (
          <CancelOutlined fontSize="inherit" />
        ) : (
          <SvgIcon fontSize="inherit">
            <path
              d="M3 12h18M7 8l-4 4 4 4M17 8l4 4-4 4"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
            />
          </SvgIcon>
        )
      }
      label={say(
        waiting
          ? "value.waitingForReview"
          : rejected
            ? "value.rejectedInReview"
            : "value.rejectedForLength",
      )}
    />
  );
}
