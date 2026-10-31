import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import TenantsPageClient from "@/app/tenants/TenantsPageClient";

vi.mock("@/lib/api/admin-tenants", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/admin-tenants")>()),
  getTenants: vi.fn(() => new Promise(() => {})),
  getPlanAlerts: vi.fn(() => new Promise(() => {})),
}));
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

describe("« Entreprises » page", () => {
  it("renders its heading", () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <TenantsPageClient />
      </QueryClientProvider>,
    );
    expect(screen.getByRole("heading", { level: 1, name: "Gestion des entreprises" })).toBeInTheDocument();
  });
});
