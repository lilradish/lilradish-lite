import Autocomplete, {
  createFilterOptions,
  type AutocompleteRenderInputParams,
} from "@mui/material/Autocomplete";
import TextField from "@mui/material/TextField";
import Box from "@mui/material/Box";
import { useId, type HTMLAttributes, type Key } from "react";

import type { GroupStanding } from "../api/standing";
import { say } from "../i18n/app";
import { isolated } from "../lib/direction/isolated";
import { KeyTag } from "../lib/text/KeyTag";

const PICKER_SX = { px: 2, pt: 1, pb: 0.5 };

const OPTION_SX = {
  display: "grid",
  gridTemplateColumns: "minmax(0, 1fr) auto",
  columnGap: "10px",
  alignItems: "start",
  width: "100%",
};

// The system's own fold, case folded and accents kept.
const NARROWED = createFilterOptions<GroupStanding>({
  ignoreAccents: false,
  stringify: (each) => `${each.name} ${each.key}`,
});

function labelOf(each: GroupStanding): string {
  return each.name;
}

function keyOf(each: GroupStanding): string {
  return each.groupId;
}

function option(
  { key, ...shown }: HTMLAttributes<HTMLLIElement> & { key: Key },
  each: GroupStanding,
) {
  return (
    <li key={key} {...shown}>
      <Box component="span" sx={OPTION_SX}>
        {isolated(each.name)}
        {each.name === each.key ? null : (
          <>
            {" "}
            <KeyTag>{each.key}</KeyTag>
          </>
        )}
      </Box>
    </li>
  );
}

function input(
  params: AutocompleteRenderInputParams,
  group: GroupStanding,
  keyId: string,
) {
  const hasKey =
    params.slotProps.htmlInput.value === group.name && group.name !== group.key;
  return (
    <TextField
      {...params}
      label={say("groupInForce.label")}
      slotProps={{
        ...params.slotProps,
        htmlInput: {
          ...params.slotProps.htmlInput,
          dir: "auto",
          "aria-describedby": hasKey ? keyId : undefined,
        },
        input: {
          ...params.slotProps.input,
          endAdornment: (
            <>
              {hasKey ? <KeyTag id={keyId}>{group.key}</KeyTag> : null}
              {params.slotProps.input.endAdornment}
            </>
          ),
        },
      }}
    />
  );
}

/**
 * Narrows the reader's own groups by name or key: a list, since it must never
 * disclose a group they are outside.
 */
export function GroupPicker({
  groups,
  group,
  onPick,
}: {
  readonly groups: readonly GroupStanding[];
  /** One of `groups`, the very same object: options are told apart by identity. */
  readonly group: GroupStanding;
  readonly onPick: (next: GroupStanding) => void;
}) {
  const keyId = useId();
  return (
    <Autocomplete
      options={groups}
      value={group}
      disableClearable
      autoHighlight
      size="small"
      sx={PICKER_SX}
      filterOptions={NARROWED}
      getOptionLabel={labelOf}
      getOptionKey={keyOf}
      noOptionsText={say("groupInForce.noMatch")}
      onChange={(_event, next) => onPick(next)}
      renderOption={option}
      renderInput={(params) => input(params, group, keyId)}
    />
  );
}
