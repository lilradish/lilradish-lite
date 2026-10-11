import FormControl from "@mui/material/FormControl";
import FormHelperText from "@mui/material/FormHelperText";
import FormLabel from "@mui/material/FormLabel";
import OutlinedInput, {
  type OutlinedInputProps,
} from "@mui/material/OutlinedInput";
import Select from "@mui/material/Select";
import type { SxProps, Theme } from "@mui/material/styles";
import { useId, type ReactNode } from "react";

import { TOKENS } from "../theme/tokens";
import { typeRoleValueInRem } from "../theme/typeRoles";

const LABEL_GAP = "5px";

const LABEL_ROOM = `${typeRoleValueInRem("lineHeight", TOKENS.type.roles.label.lineHeight)} + ${LABEL_GAP}`;

// Material's small outlined box: a line of 1.4375em in the body type role and 8.5px of
// padding above and below it, so a change to either moves this too.
const SMALL_FIELD_HEIGHT = `(1.4375 * ${typeRoleValueInRem("fontSize", TOKENS.type.roles.body.fontSize)} + 17px)`;

/** A control with no label of its own in a row of labelled fields, centred on their boxes. */
export const BESIDE_LABELLED_SX = {
  mt: `calc(${LABEL_ROOM})`,
  minHeight: `calc${SMALL_FIELD_HEIGHT}`,
  display: "flex",
  alignItems: "center",
} as const;

/** Lines in the bodyCompact type role, the first centred on the boxes and the rest falling beneath it. */
export const LINES_BESIDE_LABELLED_SX = {
  mt: `calc(${LABEL_ROOM} + (${SMALL_FIELD_HEIGHT} - ${typeRoleValueInRem("lineHeight", TOKENS.type.roles.bodyCompact.lineHeight)}) / 2)`,
} as const;

const FILLS_SX = { display: "flex", minWidth: 0 } as const;

const FITS_SX = {
  display: "flex",
  width: "fit-content",
  maxWidth: "100%",
  minWidth: 0,
} as const;

// Material's label turns the primary colour while its box holds the keyboard;
// this one keeps its colour then, and turns only for an error or disabled box.
const LABEL_SX = {
  display: "block",
  typography: "controlLabel",
  color: "text.secondary",
  mb: LABEL_GAP,
  "&.Mui-focused": { color: "text.secondary" },
  "&.Mui-error": { color: "error.main" },
  "&.Mui-disabled": { color: "text.disabled" },
} as const;

interface LabelledTextFieldProps {
  readonly label?: ReactNode;
  readonly id?: string;
  readonly value?: string;
  readonly onChange?: (event: {
    readonly target: { readonly value: string };
  }) => void;
  readonly select?: boolean;
  readonly children?: ReactNode;
  readonly helperText?: ReactNode;
  readonly helperBefore?: boolean;
  readonly describedBy?: string;
  readonly error?: boolean;
  readonly disabled?: boolean;
  readonly fullWidth?: boolean;
  readonly size?: "small" | "medium";
  readonly multiline?: boolean;
  readonly minRows?: number;
  readonly autoFocus?: boolean;
  readonly placeholder?: string;
  readonly sx?: SxProps<Theme>;
  readonly slotProps?: {
    readonly input?: Partial<OutlinedInputProps>;
    readonly htmlInput?: OutlinedInputProps["inputProps"];
    readonly select?: { readonly "aria-label"?: string };
  };
}

/**
 * A field with its label standing above it rather than inside its box, so an empty choice still shows its words.
 * The place's own width and flex settings, given as sx, go to the label and box together.
 */
export function LabelledTextField({
  label,
  id: givenId,
  value,
  onChange,
  select = false,
  children,
  helperText,
  helperBefore = false,
  describedBy,
  error,
  disabled,
  fullWidth = false,
  size,
  multiline,
  minRows,
  autoFocus,
  placeholder,
  sx,
  slotProps,
}: LabelledTextFieldProps) {
  const ownId = useId();
  const id = givenId ?? ownId;
  const labelled = label !== undefined;
  const labelId = labelled ? `${id}-label` : undefined;
  const helperId = helperText ? `${id}-helper-text` : undefined;
  const description =
    [describedBy, helperId].filter(Boolean).join(" ") || undefined;
  const helper =
    helperId === undefined ? null : (
      <FormHelperText
        id={helperId}
        sx={
          helperBefore
            ? { typography: "supportingProse", m: "0 0 5px" }
            : undefined
        }
      >
        {helperText}
      </FormHelperText>
    );
  const box = (
    <OutlinedInput
      id={select ? undefined : id}
      aria-describedby={select ? undefined : description}
      value={value}
      onChange={select ? undefined : onChange}
      fullWidth={labelled || fullWidth}
      multiline={multiline}
      minRows={minRows}
      autoFocus={autoFocus}
      placeholder={placeholder}
      inputProps={slotProps?.htmlInput}
      {...slotProps?.input}
    />
  );
  return (
    <FormControl
      error={error}
      disabled={disabled}
      size={size}
      fullWidth={labelled ? undefined : fullWidth}
      sx={
        labelled
          ? [fullWidth ? FILLS_SX : FITS_SX, ...(Array.isArray(sx) ? sx : [sx])]
          : sx
      }
    >
      {labelled ? (
        <FormLabel
          component={select ? "span" : "label"}
          id={labelId}
          htmlFor={select ? undefined : id}
          sx={LABEL_SX}
        >
          {label}
        </FormLabel>
      ) : null}
      {helperBefore ? helper : null}
      {select ? (
        <Select<string>
          id={id}
          labelId={labelId}
          aria-describedby={description}
          value={value}
          onChange={onChange}
          displayEmpty
          input={box}
          {...slotProps?.select}
        >
          {children}
        </Select>
      ) : (
        box
      )}
      {helperBefore ? null : helper}
    </FormControl>
  );
}
