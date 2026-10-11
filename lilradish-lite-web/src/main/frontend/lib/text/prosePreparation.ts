import type {
  Root as MarkdownRoot,
  RootContent,
  PhrasingContent,
  TableRow,
  Parents,
} from "mdast";
import type {
  Root as HtmlRoot,
  Element,
  Text,
  RootContent as HtmlContent,
} from "hast";
import type { State } from "mdast-util-to-hast";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import remarkRehype from "remark-rehype";
import { unified } from "unified";

import { drawingOf, walkText, type Drawing, type Piece } from "./hidden";
import { drawingTooDeep } from "./drawingDepth";

const parser = unified()
  .use(remarkParse)
  .use(remarkGfm)
  .use(keepProseContent)
  .use(remarkRehype, { handlers: { tableRow: actualTableRow } });

/** Serializable layout and character facts, shared by ordinary reads and the background reader. */
export type PreparedProse = {
  readonly drawing: Drawing;
  readonly trees: ReadonlyMap<string, HtmlRoot>;
};

/** Routing facts only: neither text length nor layout density limits what can be read. */
export function proseNeedsBackground(texts: readonly string[]): boolean {
  let characters = 0;
  let structure = 0;
  for (const text of texts) {
    characters += text.length;
    if (characters >= 256 * 1024) return true;
    let punctuation = false;
    for (const character of text) {
      if (character === "\n" || character === "\r") structure += 1;
      if (character === "|") structure += 1;
      const next = "*_`[<>~".includes(character);
      if (next && !punctuation) structure += 1;
      punctuation = next;
      if (structure >= 4096) return true;
    }
  }
  return false;
}

/** Raw leading text has no Markdown nesting; only the body is parsed. */
export function proseTooDeep(text: string): boolean {
  return drawingTooDeep(text.slice(frontMatterEnd(text)));
}

/** Parses each distinct eligible formatted text once, keeping value and block contexts independent. */
export function prepareProseTrees(
  texts: readonly string[],
  formattedTexts: readonly string[],
): PreparedProse {
  const trees = new Map<string, HtmlRoot>();
  const flows = new Map<string, readonly Flow[]>();
  const remaining = new Map<string, number>();
  for (const text of formattedTexts)
    remaining.set(text, (remaining.get(text) ?? 0) + 1);
  const visible = texts.flatMap((text) => {
    const formats = (remaining.get(text) ?? 0) > 0;
    if (formats) remaining.set(text, remaining.get(text)! - 1);
    if (!formats || proseTooDeep(text)) return [text];
    if (!trees.has(text)) {
      const end = frontMatterEnd(text);
      const body = text.slice(end);
      const tree = parser.runSync(parser.parse(body), {
        value: body,
      }) as HtmlRoot;
      removeStructuralLineBreaks(tree);
      if (end > 0)
        tree.children.unshift({
          type: "element",
          tagName: "pre",
          properties: { className: ["model-prose-front-matter"] },
          children: [
            {
              type: "element",
              tagName: "code",
              properties: {},
              children: [{ type: "text", value: text.slice(0, end) }],
            },
          ],
        });
      // micromark consumes an initial BOM before parsing. It is still part of the evidence displayed here.
      if (text.startsWith("\ufeff")) {
        const first = tree.children[0];
        const bom: Text = { type: "text", value: "\ufeff" };
        if (first?.type === "element" && /^(p|h[1-6])$/.test(first.tagName))
          first.children.unshift(bom);
        else tree.children.unshift(bom);
      }
      trees.set(text, tree);
      flows.set(text, textFlows(tree));
    }
    return flows.get(text)!.map((flow) => flow.text);
  });
  const drawing = drawingOf(visible);
  for (const [text, tree] of trees) {
    const replacements = new Map<Text, HtmlContent[]>();
    for (const flow of flows.get(text)!) {
      for (const node of flow.nodes) replacements.set(node, []);
      let cursor = 0;
      let leaf = 0;
      let leafStart = 0;
      const append = (
        from: number,
        to: number,
        marker?: Exclude<Piece, { readonly text: string }>,
      ) => {
        while (
          leaf < flow.nodes.length &&
          leafStart + flow.nodes[leaf]!.value.length <= from
        ) {
          leafStart += flow.nodes[leaf]!.value.length;
          leaf += 1;
        }
        let first = true;
        while (leaf < flow.nodes.length && leafStart < to) {
          const node = flow.nodes[leaf]!;
          const end = Math.min(to, leafStart + node.value.length);
          if (marker !== undefined) {
            if (first && !("kept" in drawing))
              replacements.get(node)!.push(markerNode(marker));
          } else {
            replacements.get(node)!.push({
              type: "text",
              value: node.value.slice(
                Math.max(from - leafStart, 0),
                end - leafStart,
              ),
            });
          }
          first = false;
          if (end === to) break;
          leafStart += node.value.length;
          leaf += 1;
        }
      };
      walkText(
        flow.text,
        (from, to) => {
          append(from, to);
          cursor = to;
        },
        (codePoint, count, unseen) => {
          const end = cursor + (codePoint > 0xffff ? 2 : 1) * count;
          append(cursor, end, { codePoint, count, unseen });
          cursor = end;
          return true;
        },
      );
    }
    const rewrite = (node: HtmlRoot | Element): void => {
      node.children = node.children.flatMap((child): HtmlContent[] => {
        if (child.type === "element") rewrite(child);
        return child.type === "text" ? replacements.get(child)! : [child];
      }) as typeof node.children;
    };
    rewrite(tree);
  }
  return { drawing, trees };
}

function frontMatterEnd(text: string): number {
  const opening = /^---\r?\n/.exec(text);
  if (opening === null) return 0;
  const closing = /\n---(?:\r?\n|$)/g;
  closing.lastIndex = opening[0].length - 1;
  const match = closing.exec(text);
  return match === null ? 0 : match.index + match[0].length;
}

type Flow = { readonly text: string; readonly nodes: readonly Text[] };
const BLOCKS = new Set([
  "p",
  "pre",
  "blockquote",
  "ul",
  "ol",
  "li",
  "table",
  "thead",
  "tbody",
  "tr",
  "td",
  "th",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "hr",
]);

/** Inline emphasis changes style, while a block or hard break starts new character context. */
function textFlows(tree: HtmlRoot): Flow[] {
  const flows: Flow[] = [];
  let nodes: Text[] = [];
  const flush = () => {
    if (nodes.length > 0) {
      flows.push({ text: nodes.map((node) => node.value).join(""), nodes });
      nodes = [];
    }
  };
  const gather = (node: HtmlRoot | HtmlContent): void => {
    if (node.type === "text") nodes.push(node);
    else if ("children" in node) {
      const block = node.type === "element" && BLOCKS.has(node.tagName);
      if (block) flush();
      node.children.forEach(gather);
      if (block) flush();
    } else flush();
    if (node.type === "element" && node.tagName === "br") flush();
  };
  gather(tree);
  flush();
  return flows;
}

function markerNode(piece: Exclude<Piece, { readonly text: string }>): Element {
  return {
    type: "element",
    tagName: "span",
    properties: {
      dataProseCode: piece.codePoint,
      dataProseCount: piece.count,
      dataProseUnseen: piece.unseen,
    },
    children: [],
  };
}

/** Converts only cells actually present, including those beyond the declared header. */
function actualTableRow(
  state: State,
  node: TableRow,
  parent?: Parents,
): Element {
  const tagName = parent?.children[0] === node ? "th" : "td";
  const cells = node.children.map((cell, column): Element => {
    const align = parent?.type === "table" ? parent.align?.[column] : undefined;
    const result: Element = {
      type: "element",
      tagName,
      properties: align ? { align } : {},
      children: state.all(cell),
    };
    state.patch(cell, result);
    return state.applyData(cell, result);
  });
  const result: Element = {
    type: "element",
    tagName: "tr",
    properties: {},
    children: state.wrap(cells, true),
  };
  state.patch(node, result);
  return state.applyData(node, result);
}

/** Retains the content Markdown's layout would otherwise discard. */
function keepProseContent() {
  return (tree: MarkdownRoot, file: { readonly value: unknown }) => {
    const source = String(file.value);
    const definitions = new Map<
      string,
      { url: string; title?: string | null }
    >();
    const gather = (node: MarkdownRoot | RootContent): void => {
      if (node.type === "definition" && !definitions.has(node.identifier))
        definitions.set(node.identifier, node);
      if ("children" in node) node.children.forEach(gather);
    };
    gather(tree);
    const rewrite = (node: MarkdownRoot | RootContent): void => {
      if (!("children" in node)) return;
      const children = node.children as RootContent[];
      for (let at = 0; at < children.length; at += 1) {
        const child = children[at]!;
        rewrite(child);
        if (
          child.type === "definition" ||
          child.type === "footnoteDefinition"
        ) {
          children[at] = literal(
            source.slice(
              child.position?.start.offset,
              child.position?.end.offset,
            ),
            child.position,
          );
        } else if (child.type === "html") {
          children[at] = {
            type: "text",
            value: child.value,
            position: child.position,
          };
        } else if (child.type === "code" && (child.lang || child.meta)) {
          children.splice(
            at,
            0,
            literal([child.lang, child.meta].filter(Boolean).join(" ")),
          );
          at += 1;
        } else if (
          child.type === "link" ||
          child.type === "image" ||
          child.type === "linkReference" ||
          child.type === "imageReference"
        ) {
          const address =
            "url" in child ? child : definitions.get(child.identifier);
          const words: PhrasingContent[] =
            "children" in child
              ? child.children
              : [
                  {
                    type: "text",
                    value: child.alt ?? "",
                    position: child.position,
                  },
                ];
          const same =
            words.every((word) => word.type === "text") &&
            words
              .map((word) => ("value" in word ? word.value : ""))
              .join("") === address?.url;
          children[at] = {
            type: "emphasis",
            data: { hName: "span" },
            children: [
              ...words,
              ...(address?.url && !same
                ? [{ type: "text" as const, value: ` (${address.url})` }]
                : []),
              ...(address?.title
                ? [{ type: "text" as const, value: ` (${address.title})` }]
                : []),
            ],
          };
        } else if (child.type === "footnoteReference") {
          children[at] = {
            type: "text",
            position: child.position,
            value: source.slice(
              child.position?.start.offset,
              child.position?.end.offset,
            ),
          };
        } else if (
          child.type === "listItem" &&
          child.checked !== null &&
          child.checked !== undefined
        ) {
          const first = child.children[0];
          if (first?.type === "paragraph")
            first.children.unshift({
              type: "text",
              value: child.checked ? "[x] " : "[ ] ",
            });
          child.checked = null;
        }
      }
    };
    rewrite(tree);
  };
}

function literal(
  value: string,
  position?: RootContent["position"],
): RootContent {
  return { type: "paragraph", children: [{ type: "text", value, position }] };
}

function removeStructuralLineBreaks(node: HtmlRoot | Element): void {
  // mdast-util-to-hast wrap/list-item synthesize unpositioned single LFs.
  // Reconstructed source text retains its original position.
  if (
    node.type === "root" ||
    ["ul", "ol", "li", "blockquote", "table", "thead", "tbody", "tr"].includes(
      node.tagName,
    )
  ) {
    node.children = node.children.filter(
      (child) =>
        !(
          child.type === "text" &&
          child.value === "\n" &&
          child.position === undefined
        ),
    );
  }
  for (const child of node.children) {
    if (
      child.type === "element" &&
      child.tagName !== "pre" &&
      child.tagName !== "code"
    )
      removeStructuralLineBreaks(child);
  }
}
