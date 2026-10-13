import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { SyncTenantDetailDrawer } from "@/components/sync/SyncTenantDetailDrawer";
import { getTenantSyncDetail } from "@/lib/api/admin-sync";
import type { AdminSyncTenantDetail } from "@/types/sync-monitoring";

vi.mock("@/lib/api/admin-sync", () => ({
  getTenantSyncDetail: vi.fn(),
}));

const mockedDetail = vi.mocked(getTenantSyncDetail);

const TENANT_ID = "3f2b1c4d-0000-4000-8000-0000000000aa";
const USER_EMP = "9a8b7c6d-1111-4111-8111-000000000001";
const USER_OWNER = "9a8b7c6d-1111-4111-8111-000000000002";
const USER_UNKNOWN = "9a8b7c6d-1111-4111-8111-000000000003";

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const CODE_PATTERN = /\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b/;
/** Any all-caps token of 3+ letters (OWNER, PRODUCT, FREE, PREMIUM…) is a raw code. */
const ALL_CAPS_PATTERN = /\b[A-Z]{3,}\b/;

const DETAIL: AdminSyncTenantDetail = {
  tenantId: TENANT_ID,
  tenantName: "Boutique Awa",
  tenantPlan: "PREMIUM_TRIAL",
  devices: [
    device("d1c0ffee-2222-4222-8222-000000000001", USER_EMP, "Awa Diallo", "EMPLOYEE"),
    device("d1c0ffee-2222-4222-8222-000000000002", USER_EMP, "Awa Diallo", "EMPLOYEE"),
    device("d1c0ffee-2222-4222-8222-000000000003", USER_OWNER, null, "OWNER"),
    device("d1c0ffee-2222-4222-8222-000000000004", USER_UNKNOWN, null, null),
  ],
  recentFailedOps: [
    { operationType: "CREATE_SALE", errorReason: "PRODUCT_NOT_FOUND", processedAt: "2026-10-12T08:30:00Z" },
    { operationType: "NEW_THING", errorReason: `Product not found: ${USER_EMP}`, processedAt: null },
  ],
  recentConflicts: [
    {
      entityType: "PRODUCT",
      conflictType: "LAST_WRITE_WINS",
      strategy: "LAST_WRITE_WINS",
      resolvedAt: "2026-10-12T09:15:00Z",
    },
  ],
};

function device(deviceId: string, userId: string, userName: string | null, userRole: string | null) {
  return { deviceId, userId, userName, userRole, lastPushAt: null, lastPullAt: null, updatedAt: null };
}

function renderDrawer(tenantId: string | null, onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const utils = render(<SyncTenantDetailDrawer tenantId={tenantId} onClose={onClose} />, { wrapper });
  return { ...utils, client, onClose };
}

describe("SyncTenantDetailDrawer", () => {
  beforeEach(() => {
    mockedDetail.mockReset();
  });

  it("renders nothing and does not fetch when no tenant is selected", () => {
    const { container } = renderDrawer(null);
    expect(container).toBeEmptyDOMElement();
    expect(mockedDetail).not.toHaveBeenCalled();
  });

  it("shows French labels and device names, never a UUID or a raw code", async () => {
    mockedDetail.mockResolvedValue(DETAIL);
    renderDrawer(TENANT_ID);

    expect(await screen.findByText("Synchronisation — Boutique Awa")).toBeInTheDocument();
    expect(screen.getByText("Plan Essai Premium")).toBeInTheDocument();

    expect(screen.getAllByText("Awa Diallo")).toHaveLength(2);
    expect(screen.getByText("Propriétaire")).toBeInTheDocument();
    expect(screen.getByText("Utilisateur inconnu")).toBeInTheDocument();
    // Numbered per user in list order.
    expect(screen.getAllByText("Appareil 1")).toHaveLength(3);
    expect(screen.getByText("Appareil 2")).toBeInTheDocument();

    expect(screen.getByText("Enregistrement de vente")).toBeInTheDocument();
    expect(screen.getByText("Produit introuvable")).toBeInTheDocument();
    expect(screen.getByText("New thing")).toBeInTheDocument();
    expect(screen.getByText("Données refusées par le serveur")).toBeInTheDocument();
    expect(screen.getByText("Produit")).toBeInTheDocument();
    expect(screen.getByText("Modifications concurrentes")).toBeInTheDocument();
    expect(screen.getByText("Dernière modification gagnante")).toBeInTheDocument();

    const text = document.body.textContent ?? "";
    expect(text).not.toMatch(UUID_PATTERN);
    expect(text).not.toMatch(CODE_PATTERN);
    expect(text).not.toMatch(ALL_CAPS_PATTERN);
  });

  it("shows « Chargement… » while the detail is pending", async () => {
    mockedDetail.mockReturnValue(new Promise(() => {}));
    renderDrawer(TENANT_ID);
    expect(await screen.findByText("Chargement…")).toBeInTheDocument();
  });

  it("closes on Escape and on the close button", async () => {
    mockedDetail.mockResolvedValue(DETAIL);
    const { onClose } = renderDrawer(TENANT_ID);
    await screen.findByText("Synchronisation — Boutique Awa");

    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole("button", { name: "Fermer" }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("shows a load error with retry when the first fetch fails", async () => {
    mockedDetail.mockRejectedValueOnce(new Error("boom")).mockResolvedValueOnce(DETAIL);
    renderDrawer(TENANT_ID);

    expect(await screen.findByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(await screen.findByText("Synchronisation — Boutique Awa")).toBeInTheDocument();
    expect(mockedDetail).toHaveBeenCalledTimes(2);
  });

  it("keeps stale data and warns when a refresh fails", async () => {
    mockedDetail.mockResolvedValueOnce(DETAIL).mockRejectedValueOnce(new Error("boom"));
    const { client } = renderDrawer(TENANT_ID);
    await screen.findByText("Synchronisation — Boutique Awa");

    await client.refetchQueries();

    await waitFor(() =>
      expect(
        screen.getByText("Échec de l'actualisation — données possiblement obsolètes"),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("Synchronisation — Boutique Awa")).toBeInTheDocument();
  });
});
