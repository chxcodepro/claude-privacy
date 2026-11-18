import type { QueryClient } from "@tanstack/react-query";
import { HeadContent, Outlet, createRootRouteWithContext } from "@tanstack/react-router";
import { ThemeProvider } from "next-themes";

import { Toaster } from "@claude-privacy/ui/components/sonner";

import { Header } from "#src/components/header.tsx";

interface RouterContext {
  queryClient: QueryClient;
}

const RootLayout = () => (
  <ThemeProvider
    attribute="class"
    defaultTheme="system"
    disableTransitionOnChange
    scriptProps={{ type: "application/json" }}
  >
    <HeadContent />
    <div className="grid min-h-svh grid-rows-[auto_1fr]">
      <Header />
      <main className="mx-auto w-full max-w-xl px-4 py-7 sm:py-10">
        <Outlet />
      </main>
    </div>
    <Toaster richColors />
  </ThemeProvider>
);

export const Route = createRootRouteWithContext<RouterContext>()({
  head: () => ({
    meta: [{ title: "Claude 隐私" }],
  }),
  component: RootLayout,
});
