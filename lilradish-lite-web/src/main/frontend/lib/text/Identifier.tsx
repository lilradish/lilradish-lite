import Box from "@mui/material/Box";

const IDENTIFIER_SX = {
  display: "inline-block",
  typography: "identifier",
  color: "text.primary",
  bgcolor: "background.default",
  borderRadius: 2,
  px: 0.75,
  py: 0.25,
  "&:hover, .MuiLink-root:focus-visible &": { bgcolor: "action.selected" },
};

export function Identifier({ value }: { readonly value: string }) {
  return (
    <Box component="span" sx={IDENTIFIER_SX}>
      {value}
    </Box>
  );
}
