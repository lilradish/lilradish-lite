import Autocomplete, {
  createFilterOptions,
  type AutocompleteRenderInputParams,
} from "@mui/material/Autocomplete";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import type { HTMLAttributes, Key } from "react";

import type { GroupStanding } from "../api/standing";
import { say } from "../i18n/app";
import { isolated } from "../lib/direction/isolated";

const PICKER_SX = { px: 2, pt: 1, pb: 0.5 };

const KEY_SX = { color: "text.secondary" };

// The system's own fold, case folded and accents kept: "elan" does not find "Élan".
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
      {isolated(each.name)}{" "}
      <Typography component="span" variant="body2" sx={KEY_SX}>
        {each.key}
      </Typography>
    </li>
  );
}

function input(params: AutocompleteRenderInputParams) {
  return (
    <TextField
      {...params}
      label={say("groupInForce.label")}
      slotProps={{
        ...params.slotProps,
        htmlInput: { ...params.slotProps.htmlInput, dir: "auto" },
      }}
    />
  );
}

/**
 * The reader's own groups, each by its name with its key beside it, narrowed
 * on either as they type: a list, since it shows nobody a group they are not in.
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
      renderInput={input}
    />
  );
}
