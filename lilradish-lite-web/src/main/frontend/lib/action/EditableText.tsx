import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import Box from "@mui/material/Box";
import ButtonBase from "@mui/material/ButtonBase";
import { useRef, type ReactNode, type Ref } from "react";

import { TOKENS } from "../theme/tokens";
import {
  EDITABLE_EDGE,
  EDITABLE_GROUND,
  EDITABLE_SX,
} from "./InlineEditableValue";

const CONTROL = "EditableText-control";

const PADDING_BLOCK = 8;

const PADDING_INLINE = 10;

const CONTROL_SHOWN = { opacity: 1 } as const;

const REACHED = `&:has(> .${CONTROL}.Mui-focusVisible)`;

const BLOCK_SX = {
  ...EDITABLE_SX,
  display: "block",
  cursor: "text",
  p: `${PADDING_BLOCK}px ${PADDING_INLINE}px`,
  m: `-${PADDING_BLOCK + EDITABLE_EDGE}px -${PADDING_INLINE + EDITABLE_EDGE}px`,
  borderRadius: TOKENS.shape.controlRadius,
  [`& > .${CONTROL}`]: {
    float: "right",
    ml: "8px",
    color: "ink.secondary",
    opacity: 0,
    "&.Mui-focusVisible": { outlineStyle: "none" },
    "& svg": { width: 14, height: 14 },
  },
  "@media (hover: hover)": {
    ...EDITABLE_SX["@media (hover: hover)"],
    [`&:hover > .${CONTROL}`]: CONTROL_SHOWN,
  },
  [REACHED]: {
    ...EDITABLE_GROUND,
    outlineStyle: "solid",
    outlineWidth: 2,
    outlineColor: "primary.main",
    outlineOffset: 2,
  },
  [`& > .${CONTROL}.Mui-focusVisible`]: CONTROL_SHOWN,
} as const;

/**
 * Text of several lines read as it is, its edit control a pencil beside it; a click on the text opens the
 * editor too, unless it ends a selection there.
 */
export function EditableText({
  label,
  onEdit,
  ref,
  children,
}: {
  readonly label: string;
  readonly onEdit: () => void;
  readonly ref?: Ref<HTMLButtonElement>;
  readonly children: ReactNode;
}) {
  const pointed = useRef(false);
  return (
    <Box
      sx={BLOCK_SX}
      onPointerDown={() => {
        pointed.current = true;
      }}
      onKeyDown={() => {
        pointed.current = false;
      }}
      onClick={(event) => {
        const byPointer = pointed.current;
        pointed.current = false;
        const selection = window.getSelection();
        // Only a pointer press ending a selection within this text leaves it
        // to be copied; the keyboard, a screen reader and script always open.
        if (
          byPointer &&
          selection !== null &&
          !selection.isCollapsed &&
          event.currentTarget.contains(selection.anchorNode)
        )
          return;
        onEdit();
      }}
    >
      <ButtonBase
        ref={ref}
        className={CONTROL}
        disableRipple
        aria-label={label}
      >
        <EditOutlinedIcon />
      </ButtonBase>
      <div>{children}</div>
    </Box>
  );
}
