import type { Element, Root, RootContent } from "hast";
import { Fragment, useLayoutEffect, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";

type DrawElement = (element: Element, children: ReactNode) => ReactNode;
type Job = { active: boolean; readonly work: () => void };

const NODES_PER_TASK = 128;
const TABLE_PARTS = new Set(["table", "thead", "tbody", "tfoot", "tr"]);

/** One cancellable queue for all texts of the opened value, including nested containers. */
export class ProseLayoutQueue {
  private jobs: (Job | undefined)[] = [];
  private head = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private cancelled = false;

  constructor(
    private readonly ready: () => void,
    private readonly failed: () => void,
  ) {}

  add(work: () => void): () => void {
    const job = { active: true, work };
    if (!this.cancelled) {
      this.jobs.push(job);
      this.schedule();
    }
    return () => {
      job.active = false;
    };
  }

  cancel(): void {
    this.cancelled = true;
    clearTimeout(this.timer);
    this.timer = undefined;
    this.jobs = [];
    this.head = 0;
  }

  private schedule(): void {
    if (this.cancelled || this.timer !== undefined) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      const job = this.jobs[this.head];
      this.jobs[this.head] = undefined;
      this.head += 1;
      try {
        // Child continuations register during this commit, before readiness is tested.
        if (job?.active) flushSync(job.work);
        if (this.cancelled) return;
        if (this.head === this.jobs.length) {
          this.jobs = [];
          this.head = 0;
          flushSync(this.ready);
        } else this.schedule();
      } catch {
        this.cancel();
        this.failed();
      }
    }, 0);
  }
}

/** Mounts each bounded fragment once; the caller keeps it hidden until the queue is ready. */
export function ProseLayout({
  tree,
  queue,
  drawElement,
}: {
  readonly tree: Root;
  readonly queue: ProseLayoutQueue;
  readonly drawElement: DrawElement;
}) {
  return (
    <Slice
      parent={tree}
      at={0}
      end={tree.children.length}
      queue={queue}
      drawElement={drawElement}
    />
  );
}

function Slice({
  parent,
  at,
  end,
  queue,
  drawElement,
}: {
  readonly parent: Root | Element;
  readonly at: number;
  readonly end: number;
  readonly queue: ProseLayoutQueue;
  readonly drawElement: DrawElement;
}) {
  const [content, setContent] = useState<ReactNode>(null);
  useLayoutEffect(
    () =>
      queue.add(() =>
        setContent(
          drawChildren(
            parent,
            at,
            end,
            { left: NODES_PER_TASK },
            queue,
            drawElement,
          ),
        ),
      ),
    [parent, at, end, queue, drawElement],
  );
  return content;
}

function drawChildren(
  parent: Root | Element,
  from: number,
  end: number,
  budget: { left: number },
  queue: ProseLayoutQueue,
  drawElement: DrawElement,
): ReactNode {
  const children: ReactNode[] = [];
  let at = from;
  for (; at < end && budget.left > 0; at += 1) {
    const child: RootContent = parent.children[at]!;
    budget.left -= 1;
    if (child.type === "text") {
      if (
        parent.type !== "element" ||
        !TABLE_PARTS.has(parent.tagName) ||
        /[^\t\n\r ]/.test(child.value)
      )
        children.push(child.value);
    } else if (child.type === "element") {
      children.push(
        <Fragment key={at}>
          {drawElement(
            child,
            drawChildren(
              child,
              0,
              child.children.length,
              budget,
              queue,
              drawElement,
            ),
          )}
        </Fragment>,
      );
    }
  }
  if (at < end) {
    // Balanced continuations avoid a component ancestor chain growing with every batch.
    const middle =
      end - at > NODES_PER_TASK ? at + Math.floor((end - at) / 2) : end;
    children.push(
      <Slice
        key={at}
        parent={parent}
        at={at}
        end={middle}
        queue={queue}
        drawElement={drawElement}
      />,
    );
    if (middle < end)
      children.push(
        <Slice
          key={middle}
          parent={parent}
          at={middle}
          end={end}
          queue={queue}
          drawElement={drawElement}
        />,
      );
  }
  return children;
}
