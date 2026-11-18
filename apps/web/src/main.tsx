import "@claude-privacy/ui/globals.css";
import { RouterProvider, createHashHistory } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { createAppRouter } from "#src/router.tsx";

const router = createAppRouter(createHashHistory());
const container = document.querySelector("#root");

if (!container) {
  throw new Error("Missing #root element");
}

createRoot(container).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
