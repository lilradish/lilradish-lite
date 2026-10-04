import Box from "@mui/material/Box";
import type { ComponentProps, ReactNode } from "react";
import Markdown, { type Components, type ExtraProps } from "react-markdown";

import { drawingTooDeep } from "./drawingDepth";

const PROSE_SX = {
  overflowWrap: "anywhere",
  "& > :first-of-type": { marginTop: 0 },
  "& > :last-child": { marginBottom: 0 },
  "& pre": { whiteSpace: "pre-wrap" },
};

const COMPONENTS: Components = {
  a: AddressShown,
  img: ImageNamed,
};

/** Text a model wrote, laid out as the Markdown it is; too deep to lay out safely, it is drawn as `plain`. */
export function ModelProse({
  text,
  plain,
}: {
  readonly text: string;
  readonly plain: ReactNode;
}) {
  if (drawingTooDeep(text)) {
    return plain;
  }
  return (
    <Box sx={PROSE_SX}>
      <Markdown components={COMPONENTS}>{text}</Markdown>
    </Box>
  );
}

function AddressShown({
  node,
  href,
  children,
  ...rest
}: ExtraProps & ComponentProps<"a">) {
  if (href === undefined || href === "") {
    return children;
  }
  const wordsAreAddress = node?.children.every(
    (child) => child.type === "text" && child.value === href,
  );
  return (
    <>
      <a {...rest} href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
      {wordsAreAddress ? null : <> ({href})</>}
    </>
  );
}

// Never an <img>: nothing a model wrote may make the page fetch.
function ImageNamed({ alt, src }: ExtraProps & ComponentProps<"img">) {
  return (
    <>
      {alt}
      {typeof src === "string" && src !== "" ? <> ({src})</> : null}
    </>
  );
}
