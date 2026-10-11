import Box from "@mui/material/Box";
import type { ReactNode } from "react";
import { TOKENS } from "../theme/tokens";

export function FieldName({
  name,
  label,
  context = "reading",
  children,
}: {
  readonly name: string;
  readonly label?: string;
  readonly context?: "author" | "reading";
  readonly children?: ReactNode;
}) {
  const reading =
    children ??
    (label || (context === "author" ? name : name.replaceAll("_", " ")));
  const compared =
    typeof reading === "string"
      ? reading.replace(/^\u2068([\s\S]*)\u2069$/u, "$1")
      : reading;
  const differentLabel = label !== undefined && label !== "" && label !== name;
  return (
    <>
      <bdi>{reading}</bdi>
      {!differentLabel || compared === name ? null : (
        <>
          {" "}
          <Box
            component="span"
            sx={{
              color: "ink.secondary",
              fontWeight: TOKENS.type.roles.body.fontWeight,
            }}
          >
            {"·"}
          </Box>{" "}
          <Box
            component="code"
            sx={{ typography: "identifier", color: "text.secondary" }}
          >
            <bdi>{name}</bdi>
          </Box>
        </>
      )}
    </>
  );
}
