import ButtonBase from "@mui/material/ButtonBase";
import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import type { Ref } from "react";

import { say } from "../../i18n/app";
import { TOKENS } from "../theme/tokens";
import { INLINE_BOX_SX, INLINE_SLOT_SX, inlineEditWidth } from "./InlineEdit";

export const EDITABLE_GROUND = {
  bgcolor: "action.hover",
  borderColor: "surface.cardEdge",
} as const;

export const EDITABLE_EDGE = 1;

export const EDITABLE_SX = {
  border: `${EDITABLE_EDGE}px solid transparent`,
  "@media (hover: hover)": { "&:hover": EDITABLE_GROUND },
  "&.Mui-focusVisible": EDITABLE_GROUND,
} as const;

export function InlineEditableValue({
  value,
  onEdit,
  disabled,
  ref,
  characters,
  label,
}: {
  readonly value: string;
  readonly onEdit: () => void;
  readonly disabled?: boolean;
  readonly ref?: Ref<HTMLButtonElement>;
  readonly characters?: number;
  readonly label?: string;
}) {
  return (
    <ButtonBase
      ref={ref}
      aria-label={label ?? say("inline.editable", { value })}
      disabled={disabled}
      onClick={onEdit}
      sx={{
        ...INLINE_SLOT_SX,
        ...INLINE_BOX_SX,
        width: characters === undefined ? "100%" : inlineEditWidth(characters),
        minHeight: TOKENS.inPlace.control,
        textAlign: "start",
        overflowWrap: "anywhere",
        ...EDITABLE_SX,
        "& > svg": {
          width: TOKENS.inPlace.icon,
          height: TOKENS.inPlace.icon,
          color: "ink.secondary",
          justifySelf: "end",
          opacity: 0,
        },
        "@media (hover: hover)": {
          "&:hover": EDITABLE_GROUND,
          "&:hover > svg": { opacity: 1 },
        },
        "&.Mui-focusVisible > svg": { opacity: 1 },
      }}
    >
      <bdi>{value}</bdi>
      <EditOutlinedIcon />
    </ButtonBase>
  );
}
