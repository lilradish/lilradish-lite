import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import {
  Component,
  createContext,
  memo,
  useContext,
  useMemo,
  type ComponentProps,
  type ReactNode,
} from "react";
import Markdown, { type Components, type ExtraProps } from "react-markdown";

import {
  isWithheldLeaf,
  type ReadField,
  type ShownValue,
  type ShownValues,
} from "../../../api/filling";
import type { WentIn } from "../../../api/stepSources";
import { say } from "../../../i18n/app";
import { codePointsIn } from "../../../lib/filling/writing";
import { Field, Fields } from "../../../lib/form/Fields";
import { HEADING_SX, UNSEEN_SX } from "../../../lib/layout/parts";
import { drawingTooDeep } from "../../../lib/text/drawingDepth";
import { ProseScope } from "../../../lib/text/ModelProse";
import "../../../lib/text/modelProseMarkdown.css";
import {
  holdsMark,
  markPlaceholders,
  marksIn,
  writtenAgain,
  type Marked,
  type Segment,
} from "../../../lib/text/placeholders";
import { FilledValue, inOneLine, labelledInLine } from "../fill/FilledValue";
import { fieldAt, labelAt, type Declaring } from "./declared";
import { WentInListed } from "./WentInListed";

type Element = NonNullable<ExtraProps["node"]>;

type Content = Element["children"][number];

interface Parent {
  children: Content[];
}

const HEADINGS = ["h1", "h2", "h3", "h4", "h5", "h6"] as const;

export type Heading = (typeof HEADINGS)[number];

const DRAWING_SX = { overflowWrap: "anywhere" };

const PARAGRAPH_SX = { my: 1 };

const DRAWN_HEADING_SX = { ...HEADING_SX, mt: 2 };

const NEVER_DRAWN = ["img"];

const BYTE_ORDER_MARK = String.fromCharCode(0xfeff);

/** The most characters a value drawn again is written out in, where its field is named after the first time. */
const MOST_DRAWN_AGAIN = 256;

// Keep the frame bytes within the same budget as the text they surround.
const NONE = 4;
const QUOTES = 2;
const BRACKETS = 2;
const COMMA = 1;
const COLON = 1;

/** Elements whose content may be a list or a description list; a value within any other stands in a line. */
const HOLDS_BLOCKS = new Set(["p", "li", "blockquote", "ol", "ul"]);

/** Added after the drawing, holding the names it draws a value by. */
const REST = "section";

interface Drawn {
  readonly segments: readonly Segment[];
  readonly wentIn: readonly WentIn[];
  readonly takes: readonly ReadField[] | undefined;
  readonly declaring: Declaring;
  readonly under: number;
}

const DrawnContext = createContext<Drawn | null>(null);

type Placed =
  | { readonly as: "written"; readonly text: string }
  | { readonly as: "none" }
  | { readonly as: "above"; readonly name: string }
  | { readonly as: "given"; readonly name: string; readonly given: WentIn }
  | {
      readonly as: "within";
      readonly name: string;
      readonly given: readonly WentIn[];
    };

// Values are inserted after drawing parsing, so their markup cannot reach the drawing or its addresses.
export const DrawnInputs = memo(function DrawnInputs({
  drawing,
  wentIn,
  takes,
  declaring,
  under,
}: {
  readonly drawing: string;
  readonly wentIn: readonly WentIn[];
  readonly takes: readonly ReadField[] | undefined;
  readonly declaring: Declaring;
  readonly under: Heading;
}) {
  const marked = useMemo(
    () =>
      // The parser drops a byte order mark opening the text and counts every offset after it.
      markPlaceholders(
        drawing.startsWith(BYTE_ORDER_MARK) ? drawing.slice(1) : drawing,
      ),
    [drawing],
  );
  const plugins = useMemo(
    () => [[placeValues, marked] as [typeof placeValues, Marked]],
    [marked],
  );
  const drawn = useMemo(
    () => ({
      segments: marked.segments,
      wentIn,
      takes,
      declaring,
      under: levelOf(under),
    }),
    [marked, wentIn, takes, declaring, under],
  );
  // Keep drawing HTML and unsafe addresses inert in the final rendered output.
  const laidOut = useMemo(
    () => (
      <Markdown
        rehypePlugins={plugins}
        disallowedElements={NEVER_DRAWN}
        components={COMPONENTS}
      >
        {marked.source}
      </Markdown>
    ),
    [plugins, marked],
  );
  // React commits and unmounts element by element recursively, outside any boundary: a deep drawing is never parsed.
  const tooDeep = useMemo(() => drawingTooDeep(drawing), [drawing]);
  const listed = (
    <>
      <Typography variant="body2" sx={PARAGRAPH_SX}>
        {say("question.drawingNotLaidOut")}
      </Typography>
      <WentInListed wentIn={wentIn} takes={takes} declaring={declaring} />
    </>
  );
  return (
    <DrawnContext value={drawn}>
      <ProseScope key={drawing}>
        <Box className="drawn-inputs-markdown" sx={DRAWING_SX}>
          {tooDeep ? (
            listed
          ) : (
            <LaidOutOrListed key={drawing} listed={listed}>
              {laidOut}
            </LaidOutOrListed>
          )}
        </Box>
      </ProseScope>
    </DrawnContext>
  );
});

// A drawing that still cannot be laid out, however the server and the depth estimate judged it.
class LaidOutOrListed extends Component<
  { readonly listed: ReactNode; readonly children: ReactNode },
  { readonly failed: boolean }
> {
  override state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  override render() {
    return this.state.failed ? this.props.listed : this.props.children;
  }
}

function useDrawn(): Drawn {
  const drawn = useContext(DrawnContext);
  if (drawn === null) {
    throw new Error("drawn outside a drawing");
  }
  return drawn;
}

function Paragraph({
  node,
  children,
  className,
}: ExtraProps & { children?: ReactNode; className?: string }) {
  const drawn = useDrawn();
  const holdsBlock = (node?.children ?? []).some((child) => {
    const placed = placedBy(child, drawn);
    return (
      placed?.as === "within" ||
      (placed?.as === "given" &&
        (drawnAsBlock(placed.given) ||
          (child.type === "element" &&
            child.properties.dataFormatted === true &&
            fieldAt(drawn.takes, placed.name)?.lines === "several")))
    );
  });
  return (
    <Box component={holdsBlock ? "div" : "p"} className={className}>
      {children}
    </Box>
  );
}

function DrawnHeading({
  node,
  children,
}: ExtraProps & { children?: ReactNode }) {
  const { under } = useDrawn();
  const written = HEADINGS.indexOf(node?.tagName as Heading) + 1;
  const tag = HEADINGS[Math.min(HEADINGS.length, under + written) - 1]!;
  return (
    <Box component={tag} className={`drawn-markdown-h${written}`}>
      {children}
    </Box>
  );
}

/**
 * A link the drawing writes opens apart from this page, which may hold an answer not yet sent; one whose address the
 * library took is its words alone, and one with no words is its address alone, as text.
 */
function Link({
  node,
  href,
  children,
  ...rest
}: ExtraProps & ComponentProps<"a">) {
  const drawn = useDrawn();
  if (href === undefined || href === "") {
    return children;
  }
  if (node !== undefined && !holdsWords(node, drawn)) {
    return href;
  }
  return (
    <a {...rest} href={href} target="_blank" rel="noopener noreferrer">
      {children}
      <Box component="span" sx={UNSEEN_SX}>
        {" "}
        {say("link.newTab")}
      </Box>
    </a>
  );
}

function DrawnSegment({ node, ...rest }: ExtraProps & ComponentProps<"span">) {
  const drawn = useDrawn();
  const placed = node === undefined ? null : placedBy(node, drawn);
  if (placed === null) {
    return <span {...rest} />;
  }
  const inLine = node?.properties.dataInLine === true;
  const { takes } = drawn;
  switch (placed.as) {
    case "written":
      return placed.text;
    case "none":
      return say("step.none");
    case "above":
      return say("question.shownAbove", { label: labelAt(takes, placed.name) });
    case "given":
      return (
        <FilledValue
          field={fieldAt(takes, placed.name)}
          label={labelAt(takes, placed.name)}
          shown={placed.given}
          shut={false}
          inLine={inLine}
          formatted={
            node?.properties.dataFormatted === true &&
            fieldAt(takes, placed.name)?.lines === "several"
          }
        />
      );
    case "within": {
      const values = placed.given.map((each) => (
        <FilledValue
          field={fieldAt(takes, each.input)}
          label={labelAt(takes, each.input)}
          shown={each}
          shut={false}
          inLine={inLine}
          formatted={false}
        />
      ));
      return inLine ? (
        inOneLine(
          placed.given.map((each, at) =>
            labelledInLine(labelAt(takes, each.input), values[at]),
          ),
        )
      ) : (
        <Fields>
          {placed.given.map((each, at) => (
            <Field key={each.input} label={labelAt(takes, each.input)}>
              {values[at]}
            </Field>
          ))}
        </Fields>
      );
    }
  }
}

/** Every input the drawing draws no value of, under a heading of its own. */
function Rest({ node }: ExtraProps) {
  const drawn = useDrawn();
  const named = node?.properties.dataDrawn;
  const drawnBy = Array.isArray(named) ? named.map(String) : [];
  const namedInputs = new Set(
    [...new Set(drawnBy)].flatMap((name) => givenFor(name, drawn.wentIn)),
  );
  const drawnHere: WentIn[] = [];
  const rest: WentIn[] = [];
  for (const each of drawn.wentIn) {
    if (namedInputs.has(each)) {
      if (each.from !== undefined || each.sources !== undefined) {
        drawnHere.push(each);
      }
    } else {
      rest.push(each);
    }
  }
  if (drawnHere.length === 0 && rest.length === 0) {
    return null;
  }
  const tag = HEADINGS[Math.min(HEADINGS.length, drawn.under + 1) - 1]!;
  return (
    <>
      {drawnHere.length === 0 ? null : (
        <WentInListed
          wentIn={drawnHere}
          takes={drawn.takes}
          declaring={drawn.declaring}
          sourcesOnly
        />
      )}
      {rest.length === 0 ? null : (
        <>
          <Typography variant={tag} sx={DRAWN_HEADING_SX}>
            {say("question.whatElseWentIn")}
          </Typography>
          <WentInListed
            wentIn={rest}
            takes={drawn.takes}
            declaring={drawn.declaring}
          />
        </>
      )}
    </>
  );
}

const COMPONENTS: Components = {
  // A value may be drawn as a list, which a paragraph may not hold.
  p: Paragraph,
  h1: DrawnHeading,
  h2: DrawnHeading,
  h3: DrawnHeading,
  h4: DrawnHeading,
  h5: DrawnHeading,
  h6: DrawnHeading,
  a: Link,
  span: DrawnSegment,
  [REST]: Rest,
};

function levelOf(heading: Heading): number {
  return HEADINGS.indexOf(heading) + 1;
}

/** Whether anything within draws a word: text other than space, or where a placeholder stands, unless only space. */
function holdsWords(content: Content, drawn: Drawn): boolean {
  switch (content.type) {
    case "text":
    case "raw":
      return /\S/u.test(content.value);
    case "element": {
      const placed = placedBy(content, drawn);
      if (placed === null) {
        return content.children.some((child) => holdsWords(child, drawn));
      }
      return (
        placed.as !== "given" ||
        !("value" in placed.given) ||
        typeof placed.given.value !== "string" ||
        /\S/u.test(placed.given.value)
      );
    }
    default:
      return false;
  }
}

/** How the placeholder an element stands for is drawn; none where it stands for none. */
function placedBy(child: Content, drawn: Drawn): Placed | null {
  if (child.type !== "element" || child.tagName !== "span") {
    return null;
  }
  const at = child.properties.dataSegment;
  if (typeof at !== "number") {
    return null;
  }
  const segment = drawn.segments[at]!;
  if ("text" in segment) {
    return { as: "written", text: segment.text };
  }
  const { name } = segment;
  const given = givenFor(name, drawn.wentIn);
  if (given.length === 0) {
    return fieldAt(drawn.takes, name) === undefined
      ? { as: "written", text: segment.written }
      : { as: "none" };
  }
  if (
    child.properties.dataAgain === true &&
    writtenPast(given, name, MOST_DRAWN_AGAIN, fieldAt(drawn.takes, name))
  ) {
    return { as: "above", name };
  }
  return given.length === 1 && given[0]!.input === name
    ? { as: "given", name, given: given[0]! }
    : { as: "within", name, given };
}

function givenFor(name: string, wentIn: readonly WentIn[]): readonly WentIn[] {
  const exact = wentIn.find((each) => each.input === name);
  return exact === undefined
    ? wentIn.filter((each) => each.input.startsWith(`${name}.`))
    : [exact];
}

function drawnAsBlock(given: WentIn): boolean {
  return (
    "value" in given && given.value !== null && typeof given.value === "object"
  );
}

/**
 * Whether what went in by `name`, written out in code points as the server writes a value out, runs past `most`; what
 * went in field by field within it written out as one value holding each by the path below `name`.
 */
function writtenPast(
  given: readonly WentIn[],
  name: string,
  most: number,
  field: ReadField | undefined,
): boolean {
  let counted = 0;
  const past = (length: number) => (counted += length) > most;
  // Past twice as many UTF-16 units, it is past as many code points, uncounted.
  const text = (said: string) =>
    past(QUOTES + (said.length > 2 * most ? most + 1 : codePointsIn(said)));
  const framed = (held: number) =>
    past(BRACKETS + Math.max(0, held - 1) * COMMA);
  const value = (
    said: ShownValue,
    declared: ReadField | undefined,
  ): boolean => {
    if (isWithheldLeaf(said)) {
      return false;
    }
    if (said === null) {
      return past(NONE);
    }
    if (typeof said === "string") {
      return text(said);
    }
    if (Array.isArray(said)) {
      return framed(said.length) || said.some((each) => value(each, declared));
    }
    const members = Object.entries(said as ShownValues);
    return (
      framed(members.length) ||
      members.some(
        ([key, each]) =>
          text(key) ||
          past(COLON) ||
          value(
            each,
            declared?.kind === "fields"
              ? declared.fields?.find((child) => child.name === key)
              : undefined,
          ),
      )
    );
  };
  const entry = (each: WentIn) =>
    "value" in each
      ? value(each.value, field)
      : "asKept" in each && each.asKept !== null
        ? text(each.asKept)
        : past(NONE);
  if (given.length === 1 && given[0]!.input === name) {
    return entry(given[0]!);
  }
  return (
    framed(given.length) ||
    given.some(
      (each) =>
        text(each.input.slice(name.length + 1)) || past(COLON) || entry(each),
    )
  );
}

function placeValues({ source, segments }: Marked) {
  const alone = new Set(
    source.split(/\r\n|\r|\n/).flatMap((line) => {
      const pieces = marksIn(line.replace(/^[ \t]*|[ \t]*$/g, ""));
      return pieces.length === 1 && typeof pieces[0] === "number"
        ? [pieces[0]]
        : [];
    }),
  );
  return (tree: Parent) => {
    const named = new Set<string>();
    within(tree, false, { source, segments, named, alone });
    tree.children.push({
      type: "element",
      tagName: REST,
      properties: { dataDrawn: [...named] },
      children: [],
    });
  };
}

interface Walk {
  readonly source: string;
  readonly segments: readonly Segment[];
  /** Every name a value has been drawn by so far, in the order the drawing is read. */
  readonly named: Set<string>;
  readonly alone: ReadonlySet<number>;
}

function within(parent: Parent, inLine: boolean, walk: Walk) {
  let at = 0;
  while (at < parent.children.length) {
    const child = parent.children[at]!;
    if (child.type === "element" && namesAValue(child)) {
      parent.children.splice(at, 1, ...child.children);
      continue;
    }
    const drawn = drawnAs(child, inLine, walk);
    parent.children.splice(at, 1, ...drawn);
    at += drawn.length;
  }
}

/** Whether a link's address or title names a value, which no value is ever drawn as. */
function namesAValue(element: Element): boolean {
  return (
    element.tagName === "a" &&
    [element.properties.href, element.properties.title].some(
      (said) => said !== undefined && said !== null && holdsMark(String(said)),
    )
  );
}

function drawnAs(child: Content, inLine: boolean, walk: Walk): Content[] {
  const { source, segments, named, alone } = walk;
  switch (child.type) {
    case "text":
      return marksIn(child.value).map((piece): Content => {
        if (typeof piece === "string") {
          return { type: "text", value: piece };
        }
        const segment = segments[piece]!;
        const again = "name" in segment && named.has(segment.name);
        if ("name" in segment) {
          named.add(segment.name);
        }
        return {
          type: "element",
          tagName: "span",
          properties: {
            dataSegment: piece,
            dataInLine: inLine,
            dataAgain: again,
            dataFormatted: !inLine && alone.has(piece),
          },
          children: [],
        };
      });
    case "raw":
      return [{ ...child, value: writtenAgain(child.value, segments) }];
    case "element": {
      if (child.tagName === "img") {
        const start = child.position?.start.offset;
        const end = child.position?.end.offset;
        return start === undefined || end === undefined
          ? []
          : [
              {
                type: "text",
                value: writtenAgain(source.slice(start, end), segments),
              },
            ];
      }
      for (const [key, value] of Object.entries(child.properties)) {
        if (holdsMark(String(value))) {
          delete child.properties[key];
        }
      }
      // Only the drawing's parsed nodes carry its styles; inserted values keep their own presentation.
      const classes = child.properties.className;
      child.properties.className = [
        ...(Array.isArray(classes)
          ? classes.map(String)
          : typeof classes === "string"
            ? [classes]
            : []),
        `drawn-markdown-${child.tagName}`,
      ];
      within(child, inLine || !HOLDS_BLOCKS.has(child.tagName), walk);
      return [child];
    }
    default:
      return [child];
  }
}
