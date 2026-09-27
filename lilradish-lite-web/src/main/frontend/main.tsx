import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter } from "react-router";
import { RouterProvider } from "react-router/dom";

import { ROUTES } from "./app/routes";
import { reportCaught, reportRouterError } from "./lib/report/reportCaught";
import { theme } from "./lib/theme/theme";

const root = document.getElementById("root");
if (root === null) {
  throw new Error("the document this mounts into carries no #root element");
}

createRoot(root, {
  onCaughtError: reportCaught,
  onUncaughtError: reportCaught,
  onRecoverableError: reportCaught,
}).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <RouterProvider
        router={createBrowserRouter(ROUTES)}
        onError={reportRouterError}
      />
    </ThemeProvider>
  </StrictMode>,
);
