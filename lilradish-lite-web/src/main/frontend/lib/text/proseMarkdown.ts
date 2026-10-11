import type { Root as HtmlRoot, Element } from "hast";
import { toJsxRuntime } from "hast-util-to-jsx-runtime";
import { Fragment, jsx, jsxs } from "react/jsx-runtime";
import {
  cloneElement,
  createElement,
  type ReactElement,
  type ReactNode,
} from "react";
import { say } from "../../i18n/lib";
import { prepareProseTrees, type PreparedProse } from "./prosePreparation";
import { ProseLayout, type ProseLayoutQueue } from "./ProseLayout";

/** Ordinary reads share the exact preparation used by the background reader. */
export function prepareProse(
  texts: readonly string[],
  formattedTexts: readonly string[] = texts,
) {
  return renderProse(prepareProseTrees(texts, formattedTexts));
}

/** Turns a finished serializable layout into elements on the reading thread. */
export function renderProse(prepared: PreparedProse) {
  const rendered = new Map<string, ReactNode>();
  for (const [text, tree] of prepared.trees) {
    labelCharacters(tree);
    rendered.set(text, toJsxRuntime(tree, { Fragment, jsx, jsxs }));
  }
  return { drawing: prepared.drawing, rendered };
}

/** Shares preparation and labels, while deferring conversion and DOM work to the opened value's queue. */
export function renderProseInParts(
  prepared: PreparedProse,
  queue: ProseLayoutQueue,
) {
  const rendered = new Map<string, ReactNode>();
  for (const [text, tree] of prepared.trees)
    rendered.set(
      text,
      createElement(ProseLayout, { tree, queue, drawElement }),
    );
  return { drawing: prepared.drawing, rendered };
}

function drawElement(element: Element, children: ReactNode): ReactNode {
  const shallow: Element = {
    ...element,
    properties: { ...element.properties },
    children: [],
  };
  const point = shallow.properties.dataProseCode;
  if (typeof point === "number") {
    const labelled: HtmlRoot = { type: "root", children: [shallow] };
    labelCharacters(labelled);
    return toJsxRuntime(shallow, { Fragment, jsx, jsxs });
  }
  if (element.children.length === 0)
    return toJsxRuntime(shallow, { Fragment, jsx, jsxs });
  return cloneElement(
    toJsxRuntime(shallow, { Fragment, jsx, jsxs }) as ReactElement,
    undefined,
    children,
  );
}

function labelCharacters(node: HtmlRoot | Element): void {
  for (const child of node.children) {
    if (child.type !== "element") continue;
    const point = child.properties.dataProseCode;
    if (typeof point === "number") {
      const values = {
        code: point.toString(16).toUpperCase().padStart(4, "0"),
        count: Number(child.properties.dataProseCount),
      };
      child.properties = {
        className: ["prose-character"],
        role: "img",
        dir: "ltr",
        title: say(
          child.properties.dataProseUnseen === "shows_nothing"
            ? "text.showsNothing"
            : "text.turnsOrder",
          values,
        ),
      };
      child.children = [{ type: "text", value: say("text.marker", values) }];
    } else labelCharacters(child);
  }
}
