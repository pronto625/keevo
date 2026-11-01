import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TenantModulesSection } from "@/components/modules/TenantModulesSection";
import { addDays, formatIsoDate, todayWat } from "@/components/tenants/planDates";
import {
  activateModule,
  deactivateModule,
  getModuleCatalogue,
  getModuleHistory,
  getTenantModules,
} from "@/lib/api/admin-modules";
import { applyBundle, getBundles } from "@/lib/api/admin-bundles";
import type { CatalogueModule, TenantModule } from "@/types/modules";

vi.mock("@/lib/api/admin-modules", () => ({
  getModuleCatalogue: vi.fn(),
  getTenantModules: vi.fn(),
  getModuleHistory: vi.fn(),
  activateModule: vi.fn(),
  deactivateModule: vi.fn(),
}));
vi.mock("@/lib/api/admin-bundles", () => ({ getBundles: vi.fn(), applyBundle: vi.fn() }));

const TENANT = "3f2b1c4d-0000-4000-8000-0000000000aa";
const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const RAW_CODE = /\b(ENABLED|READ_ONLY|DISABLED|TRIAL|ACTIVE|PLAN|ADMIN|SYSTEM|MODULE_[A-Z_]+)\b/;

const CATALOGUE: CatalogueModule[] = [
  { moduleId: "core", displayName: "Socle", category: "core", free: true, version: "1.0.0", requires: [], migrations: false },
  { moduleId: "inventory", displayName: "Stock et inventaire", category: "business", free: true, version: "1.0.0", requires: ["core"], migrations: false },
  { moduleId: "counting", displayName: "Inventaire assisté", category: "premium", free: false, version: "1.1.0", requires: ["inventory"], migrations: true },
  { moduleId: "invoicing", displayName: "Facturation", category: "premium", free: false, version: "1.4.0", requires: ["core"], migrations: true },
];

const MODULES: TenantModule[] = [
  { moduleId: "core", displayName: "Socle", state: "ENABLED", status: null, expiresAt: null, graceUntil: null, provisionedVersion: null, origin: null },
  { moduleId: "inventory", displayName: "Stock et inventaire", state: "ENABLED", status: null, expiresAt: null, graceUntil: null, provisionedVersion: null, origin: null },
  { moduleId: "counting", displayName: "Inventaire assisté", state: "ENABLED", status: "TRIAL", expiresAt: "2026-12-31T22:59:59Z", graceUntil: "2027-01-07T22:59:59Z", provisionedVersion: "2", origin: "PLAN" },
  { moduleId: "invoicing", displayName: "Facturation", state: "DISABLED", status: "DISABLED", expiresAt: null, graceUntil: null, provisionedVersion: null, origin: "ADMIN" },
];

function renderSection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TenantModulesSection tenantId={TENANT} />
    </QueryClientProvider>,
  );
}

function rowOf(name: string): HTMLElement {
  return screen.getAllByRole("cell", { name })[0].closest("tr") as HTMLElement;
}

describe("TenantModulesSection (Story 16.4)", () => {
  beforeEach(() => {
    vi.mocked(getModuleCatalogue).mockResolvedValue(CATALOGUE);
    vi.mocked(getTenantModules).mockResolvedValue(MODULES);
    vi.mocked(getModuleHistory).mockResolvedValue([
      { occurredAt: "2026-11-01T09:00:00Z", action: "Pack appliqué", subject: "Pack Pro",
        detail: "Modules activés : Facturation · Actif jusqu'au 30/06/2027", actorName: "Administrateur Keevo",
        actorPhone: "+237690000000" },
    ]);
    vi.mocked(getBundles).mockResolvedValue([
      { id: "pro", name: "Pack Pro", description: null, applicable: true,
        modules: [{ moduleId: "invoicing", displayName: "Facturation" }] },
      { id: "old", name: "Ancien pack", description: null, applicable: false, modules: [] },
    ]);
    vi.mocked(activateModule).mockReset();
    vi.mocked(deactivateModule).mockReset();
    vi.mocked(applyBundle).mockReset();
  });

  it("lists each module with its French state, JJ/MM/AAAA dates and origin; history; no id nor raw code", async () => {
    renderSection();
    await screen.findByText("Inventaire assisté");

    const counting = rowOf("Inventaire assisté");
    expect(within(counting).getByText("Essai")).toBeInTheDocument();
    expect(within(counting).getByText("31/12/2026")).toBeInTheDocument();
    expect(within(counting).getByText("07/01/2027")).toBeInTheDocument();
    expect(within(counting).getByText("Offre")).toBeInTheDocument();
    expect(within(rowOf("Facturation")).getByText("Désactivé")).toBeInTheDocument();
    expect(within(rowOf("Facturation")).getByText("Super-admin")).toBeInTheDocument();
    expect(within(rowOf("Stock et inventaire")).getByText("Inclus")).toBeInTheDocument();
    expect(within(rowOf("Socle")).queryByRole("button")).toBeNull();

    expect(await screen.findByText("Pack appliqué")).toBeInTheDocument();
    expect(screen.getByText(/Administrateur Keevo/)).toBeInTheDocument();
    const text = document.body.textContent ?? "";
    expect(text).not.toMatch(UUID_PATTERN);
    expect(text).not.toMatch(RAW_CODE);
  });

  it("activation of a paid module: Essai without a date shows the 14-day hint and sends no date", async () => {
    vi.mocked(activateModule).mockResolvedValue({ activated: [{ moduleId: "invoicing", displayName: "Facturation" }],
      status: "TRIAL", expiresAt: null, provisionedVersion: null });
    const user = userEvent.setup();
    renderSection();
    await user.click(within(await waitFor(() => rowOf("Facturation"))).getByRole("button", { name: "Activer" }));

    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Activer" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Une date de fin est requise pour un module payant",
    );
    expect(activateModule).not.toHaveBeenCalled();

    await user.selectOptions(within(dialog).getByRole("combobox"), "TRIAL");
    expect(within(dialog).getByText(`Sans date : essai de 14 jours, jusqu'au ${formatIsoDate(addDays(todayWat(), 14))}`))
      .toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Activer" }));

    await waitFor(() =>
      expect(activateModule).toHaveBeenCalledWith(TENANT, "invoicing", { status: "TRIAL", expiresAt: null }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Modules activés : Facturation");
  });

  it("deactivation with dependents: the 409 lists them, « Tout désactiver » retries with cascade", async () => {
    vi.mocked(deactivateModule)
      .mockRejectedValueOnce({
        response: { data: { error: "Désactivation impossible", domainCode: "MODULE_HAS_DEPENDENTS",
          details: { modules: ["Inventaire assisté"] } } },
      })
      .mockResolvedValueOnce({ moduleId: "inventory", displayName: "Stock et inventaire", status: "DISABLED",
        deactivated: [{ moduleId: "counting", displayName: "Inventaire assisté" },
          { moduleId: "inventory", displayName: "Stock et inventaire" }], forced: [] });
    const user = userEvent.setup();
    renderSection();
    await user.click(within(await waitFor(() => rowOf("Stock et inventaire"))).getByRole("button", { name: "Désactiver" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Désactiver" }));

    expect(await within(dialog).findByText(/Désactiver aussi : Inventaire assisté/)).toBeInTheDocument();
    expect(within(dialog).getByText(/restent désactivés même quand l'offre est renouvelée/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Tout désactiver" }));

    await waitFor(() =>
      expect(deactivateModule).toHaveBeenLastCalledWith(TENANT, "inventory", { cascade: true, force: false }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Modules désactivés : Inventaire assisté, Stock et inventaire",
    );
  });

  it("a failed shutdown shows the French error and « Forcer », which retries with force", async () => {
    vi.mocked(deactivateModule)
      .mockRejectedValueOnce({
        response: { data: { error: "Le module « Inventaire assisté » n'a pas été désactivé : sa mise hors service a échoué. Vous pouvez réessayer.",
          domainCode: "MODULE_DEACTIVATION_FAILED" } },
      })
      .mockResolvedValueOnce({ moduleId: "counting", displayName: "Inventaire assisté", status: "DISABLED",
        deactivated: [{ moduleId: "counting", displayName: "Inventaire assisté" }],
        forced: [{ moduleId: "counting", displayName: "Inventaire assisté" }] });
    const user = userEvent.setup();
    renderSection();
    await user.click(within(await waitFor(() => rowOf("Inventaire assisté"))).getByRole("button", { name: "Désactiver" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByRole("button", { name: "Forcer" })).toBeNull();
    await user.click(within(dialog).getByRole("button", { name: "Désactiver" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("sa mise hors service a échoué");
    await user.click(within(dialog).getByRole("button", { name: "Forcer" }));
    await waitFor(() =>
      expect(deactivateModule).toHaveBeenLastCalledWith(TENANT, "counting", { cascade: false, force: true }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("désactivation forcée");
  });

  it("apply a pack: inapplicable packs disabled; the pack and terms are sent", async () => {
    vi.mocked(applyBundle).mockResolvedValue({ name: "Pack Pro",
      activated: [{ moduleId: "invoicing", displayName: "Facturation" }], alreadyActive: [] });
    const user = userEvent.setup();
    renderSection();
    await screen.findByText("Facturation");
    await user.click(screen.getByRole("button", { name: "Appliquer un pack" }));
    const dialog = screen.getByRole("dialog");
    const [packSelect] = within(dialog).getAllByRole("combobox");
    expect(within(packSelect).getByRole("option", { name: "Ancien pack (inapplicable)" })).toBeDisabled();
    await user.selectOptions(packSelect, "pro");
    const end = addDays(todayWat(), 120);
    await user.type(dialog.querySelector('input[type="date"]') as HTMLInputElement, end);
    await user.click(within(dialog).getByRole("button", { name: "Appliquer" }));

    await waitFor(() => expect(applyBundle).toHaveBeenCalledWith(TENANT, "pro", { status: "ACTIVE", expiresAt: end }));
    expect(await screen.findByRole("status")).toHaveTextContent("Pack Pro appliqué — modules activés : Facturation");
  });

  it("no action while the catalogue is not loaded (paid / core unknown); its failure is said", async () => {
    vi.mocked(getModuleCatalogue).mockRejectedValue(new Error("down"));
    renderSection();
    await screen.findByText("Inventaire assisté");
    expect(await screen.findByText(/les actions sont indisponibles/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Activer" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Désactiver" })).toBeNull();
    expect(screen.getByRole("button", { name: "Appliquer un pack" })).toBeDisabled();
  });
});
