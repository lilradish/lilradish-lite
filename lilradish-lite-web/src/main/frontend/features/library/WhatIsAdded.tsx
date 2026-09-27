import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId } from "react";

import type { AddedField } from "../../api/declaration";
import { say } from "../../i18n/app";
import { isolatedInText } from "../../lib/direction/isolated";
import { FIELD_KIND_WORDS, partsSaid } from "./declarationWords";

const PART_SX = { mb: 3 };

const HEADING_SX = { mb: 1 };

const QUIET_SX = { color: "text.secondary" };

// Its markers taken away, so it is named a list outright.
const LIST_SX = { listStyleType: "none", m: 0, p: 0 };

const HELD_SX = { listStyleType: "none", m: 0, pl: 3 };

/** What a model is asked for on top of a question's instruction, as the server worked it out; only read. */
export function WhatIsAdded({
  added,
}: {
  /** Absent where the declared answer is not whole yet. */
  readonly added: readonly AddedField[] | undefined;
}) {
  const headingId = useId();
  return (
    <Box component="section" aria-labelledby={headingId} sx={PART_SX}>
      <Typography id={headingId} variant="h6" component="h3" sx={HEADING_SX}>
        {say("question.added")}
      </Typography>
      <Typography variant="body2" sx={QUIET_SX}>
        {say("question.addedHint")}
      </Typography>
      {added === undefined ? (
        <Typography variant="body2" sx={QUIET_SX}>
          {say("question.addedNotYet")}
        </Typography>
      ) : (
        <Told fields={added} labelledBy={headingId} />
      )}
    </Box>
  );
}

function Told({
  fields,
  labelledBy,
}: {
  readonly fields: readonly AddedField[];
  readonly labelledBy?: string;
}) {
  return (
    <Box
      component="ul"
      role="list"
      aria-labelledby={labelledBy}
      sx={labelledBy === undefined ? HELD_SX : LIST_SX}
    >
      {fields.map((field) => (
        <li key={field.name}>
          <Typography variant="body1">
            <code>{field.name}</code>
          </Typography>
          <Typography variant="body2" sx={QUIET_SX}>
            {toldOf(field)}
          </Typography>
          {field.terms === undefined ? null : (
            <Box component="ul" role="list" sx={HELD_SX}>
              {field.terms.map((each) => (
                <li key={each.term}>
                  <Typography variant="body2">
                    {say("added.term", {
                      term: isolatedInText(each.term),
                      meaning: isolatedInText(each.meaning),
                    })}
                  </Typography>
                </li>
              ))}
            </Box>
          )}
          {field.note === undefined ? null : (
            <Typography variant="body2" sx={QUIET_SX}>
              {say("added.note", { note: isolatedInText(field.note) })}
            </Typography>
          )}
          {field.confidence ? (
            <Typography variant="body2">{say("added.confidence")}</Typography>
          ) : null}
          {field.fields === undefined ? null : <Told fields={field.fields} />}
        </li>
      ))}
    </Box>
  );
}

/** What it is, how long, how many and whether it must be given, in the order a reader asks them. */
function toldOf(field: AddedField): string {
  const parts = [say(FIELD_KIND_WORDS[field.kind])];
  if (field.longest !== undefined) {
    parts.push(say("declaration.longestSaid", { longest: field.longest }));
  }
  parts.push(
    !field.many
      ? say("declaration.one")
      : field.most === undefined
        ? say("declaration.manyUnsaid")
        : say("declaration.manyUpTo", { most: field.most }),
    say(field.mustBeGiven ? "declaration.given" : "declaration.mayBeEmpty"),
  );
  return partsSaid(parts);
}
