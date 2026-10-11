import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import { useId } from "react";

export function InfoMark({
  about,
  hint,
}: {
  readonly about: string;
  readonly hint: string;
}) {
  const hintId = useId();
  return (
    <>
      <Tooltip title={hint} describeChild>
        <IconButton
          aria-label={about}
          aria-describedby={hintId}
          sx={{ my: "-4px" }}
        >
          <InfoOutlinedIcon />
        </IconButton>
      </Tooltip>
      <span id={hintId} hidden>
        {hint}
      </span>
    </>
  );
}
