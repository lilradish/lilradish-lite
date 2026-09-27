import MenuItem from "@mui/material/MenuItem";
import TextField from "@mui/material/TextField";

import type { HeldModel } from "../../api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}";
import { say } from "../../i18n/app";

/** A model the deployment holds and one of its modes, nothing typed; a choice no longer held is kept in sight. */
export function ModelControls({
  models,
  model,
  mode,
  change,
}: {
  readonly models: readonly HeldModel[];
  readonly model: string;
  readonly mode: string;
  readonly change: (model: string, mode: string) => void;
}) {
  const held = models.find((each) => each.name === model);
  const names = [
    ...(model === "" || held !== undefined ? [] : [model]),
    ...models.map((each) => each.name),
  ];
  const modes = [
    ...(mode === "" || held?.modes.includes(mode) === true ? [] : [mode]),
    ...(held?.modes ?? []),
  ];
  return (
    <>
      <TextField
        select
        size="small"
        label={say("workflow.model")}
        value={model}
        onChange={(edit) => change(edit.target.value, "")}
      >
        <MenuItem value="">{say("workflow.modelNone")}</MenuItem>
        {names.map((name) => (
          <MenuItem key={name} value={name}>
            {name}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        select
        size="small"
        label={say("workflow.mode")}
        value={mode}
        onChange={(edit) => change(model, edit.target.value)}
      >
        <MenuItem value="">{say("workflow.modeAsItIs")}</MenuItem>
        {modes.map((name) => (
          <MenuItem key={name} value={name}>
            {name}
          </MenuItem>
        ))}
      </TextField>
    </>
  );
}

export function modelSaid(model: string, mode: string): string {
  if (model === "") {
    return say("workflow.producesUnchosen");
  }
  return mode === ""
    ? say("workflow.modelAsItIs", { model })
    : say("workflow.modelInMode", { model, mode });
}
