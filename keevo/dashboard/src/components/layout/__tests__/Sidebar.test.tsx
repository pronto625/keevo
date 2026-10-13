import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Sidebar } from "@/components/layout/Sidebar";
import { getUnhandledHighCount } from "@/lib/api/admin-feedback";

vi.mock("next/navigation", () => ({
  usePathname: () => "/tenants",
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock("@/lib/api/admin-feedback", () => ({ getUnhandledHighCount: vi.fn() }));

const count = vi.mocked(getUnhandledHighCount);

function renderSidebar() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <Sidebar />
    </QueryClientProvider>,
  );
}

describe("Sidebar (spec 9-1 completion)", () => {
  beforeEach(() => count.mockReset());

  it("says « Entreprises », never « Tenants », and shows the unhandled HIGH feedback badge", async () => {
    count.mockResolvedValue(3);
    renderSidebar();

    expect(screen.getByRole("link", { name: "Entreprises" })).toHaveAttribute("href", "/tenants");
    expect(await screen.findByLabelText("3 retours prioritaires non traités")).toHaveTextContent("3");
    expect(screen.getByRole("link", { name: /Retours utilisateurs/ })).toHaveAttribute("href", "/retours");
    expect(document.body.textContent?.toLowerCase()).not.toContain("tenant");
  });

  it("no badge when nothing is waiting", async () => {
    count.mockResolvedValue(0);
    renderSidebar();
    await screen.findByText("Retours utilisateurs");
    expect(screen.queryByLabelText(/retours? prioritaires?/)).not.toBeInTheDocument();
  });
});
