import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import PacksPageClient from "@/app/packs/PacksPageClient";
import { deleteBundle, getBundles, saveBundle } from "@/lib/api/admin-bundles";
import { getModuleCatalogue } from "@/lib/api/admin-modules";

vi.mock("@/lib/api/admin-bundles", () => ({ getBundles: vi.fn(), saveBundle: vi.fn(), deleteBundle: vi.fn() }));
vi.mock("@/lib/api/admin-modules", () => ({ getModuleCatalogue: vi.fn() }));
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PacksPageClient />
    </QueryClientProvider>,
  );
}

describe("PacksPageClient (Story 16.4)", () => {
  it("lists packs with « Inapplicable »; editing one with an unavailable module lets it be unticked and saved", async () => {
    vi.mocked(getModuleCatalogue).mockResolvedValue([
      { moduleId: "core", displayName: "Socle", category: "core", free: true, version: "1.0.0", requires: [], migrations: false },
      { moduleId: "invoicing", displayName: "Facturation", category: "premium", free: false, version: "1.4.0", requires: [], migrations: true },
    ]);
    vi.mocked(getBundles).mockResolvedValue([{ id: "pro", name: "Pack Pro", description: "Caisse et factures",
      applicable: false, modules: [{ moduleId: "invoicing", displayName: "Facturation" },
        { moduleId: "gone", displayName: "Module indisponible" }] }]);
    vi.mocked(saveBundle).mockResolvedValue({ id: "pro", name: "Pack Pro", description: "Caisse et factures",
      applicable: true, modules: [{ moduleId: "invoicing", displayName: "Facturation" }] });
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("Inapplicable")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Modifier" }));
    const form = screen.getByRole("dialog", { name: "Modifier le pack" });
    expect(within(form).queryByRole("checkbox", { name: /Socle/ })).toBeNull(); // core never in a pack
    await user.click(within(form).getByRole("checkbox", { name: "Module indisponible" }));
    await user.click(within(form).getByRole("button", { name: "Enregistrer" }));

    await waitFor(() => expect(saveBundle).toHaveBeenCalledWith("pro",
      { name: "Pack Pro", description: "Caisse et factures", modules: ["invoicing"] }));
    expect(await screen.findByRole("status")).toHaveTextContent("Pack « Pack Pro » enregistré");
  });

  it("delete says that accounts keep their modules", async () => {
    vi.mocked(getModuleCatalogue).mockResolvedValue([]);
    vi.mocked(getBundles).mockResolvedValue([{ id: "pro", name: "Pack Pro", description: null, applicable: true,
      modules: [] }]);
    vi.mocked(deleteBundle).mockResolvedValue();
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Supprimer" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Les comptes gardent leurs modules.")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Supprimer" }));
    await waitFor(() => expect(deleteBundle).toHaveBeenCalledWith("pro"));
  });
});
