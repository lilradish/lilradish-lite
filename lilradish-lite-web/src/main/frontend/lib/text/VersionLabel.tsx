import Box from "@mui/material/Box";

import { say } from "../../i18n/app";
import { isolatedInText } from "../direction/isolated";

const NAME_FOLLOWS = " · ";

interface Labelled {
  readonly number: number;
  readonly name: string | null;
  /** The entry it is a version of, said before it where the place has not said it already. */
  readonly entry?: string;
  /** Words standing for "Version {number}" where the place says the number its own way. */
  readonly numberLabel?: string;
}

function numberSaid({ number, numberLabel }: Labelled): string {
  return numberLabel ?? say("version.number", { number });
}

/** The words VersionLabel draws, for a place that holds plain text alone; each name arrives isolated. */
export function versionLabelText(labelled: Labelled): string {
  const { entry, name } = labelled;
  return `${entry === undefined ? "" : `${isolatedInText(entry)}${NAME_FOLLOWS}`}${numberSaid(labelled)}${name === null ? "" : `${NAME_FOLLOWS}${isolatedInText(name)}`}`;
}

/** The entry and the optional current name are each isolated from the surrounding text, the name quieter. */
export function VersionLabel(labelled: Labelled) {
  const { entry, name } = labelled;
  return (
    <Box component="span">
      {entry === undefined ? null : (
        <>
          <bdi dir="auto">{entry}</bdi>
          {NAME_FOLLOWS}
        </>
      )}
      {numberSaid(labelled)}
      {name === null ? null : (
        <>
          {NAME_FOLLOWS}
          <Box component="bdi" dir="auto" sx={{ color: "text.secondary" }}>
            {name}
          </Box>
        </>
      )}
    </Box>
  );
}
