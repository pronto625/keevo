import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { PlanSection } from "@/components/tenants/PlanSection";
import {
  activatePremium,
  downgradeToFree,
  getTenantPayments,
  reactivateTenant,
  suspendTenant,
} from "@/lib/api/admin-tenants";
import type { TenantStatus, TenantSubscription } from "@/types/tenant";

vi.mock("@/lib/api/admin-tenants", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/admin-tenants")>();
  return {
    ...actual,
    activatePremium: vi.fn(),
    downgradeToFree: vi.fn(),
    suspendTenant: vi.fn(),
    reactivateTenant: vi.fn(),
    getTenantPayments: vi.fn(),
  };
});

const TENANT_ID = "3f2b1c4d-0000-4000-8000-0000000000aa";
const ALL_CAPS = /\b[A-Z]{3,}(_[A-Z]+)*\b/;

function renderSection(status: TenantStatus, subscription: TenantSubscription | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const utils = render(<PlanSection tenantId={TENANT_ID} status={status} subscription={subscription} />, {
    wrapper,
  });
  return { ...utils, invalidate, user: userEvent.setup() };
}

describe("PlanSection", () => {
  beforeEach(() => {
    vi.mocked(getTenantPayments).mockResolvedValue([]);
  });

  it("shows the current plan, its WAT expiry and the actions of an ACTIVE trial tenant, no ids or codes", async () => {
    const { container } = renderSection("ACTIVE", { planType: "PREMIUM_TRIAL", expiresAt: "2027-01-31T22:59:59Z" });

    expect(screen.getByText("Essai Premium")).toBeInTheDocument();
    expect(screen.getByText("31/01/2027")).toBeInTheDocument();
    expect(screen.getByText("Actif")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Passer en Premium" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Repasser en Gratuit" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Suspendre" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Réactiver" })).not.toBeInTheDocument();
    expect(await screen.findByText("Aucun paiement enregistré")).toBeInTheDocument();

    const text = container.textContent ?? "";
    expect(text).not.toContain(TENANT_ID);
    expect(text).not.toMatch(ALL_CAPS);
  });

  it("FREE + SUSPENDED: no downgrade, « Réactiver » instead of « Suspendre »", () => {
    renderSection("SUSPENDED", { planType: "FREE", expiresAt: null });

    expect(screen.getByText("Gratuit")).toBeInTheDocument();
    expect(screen.getByText("Aucune")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Repasser en Gratuit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Suspendre" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Réactiver" })).toBeInTheDocument();
  });

  it("activates Premium, shows a success toast and refreshes the queries", async () => {
    vi.mocked(activatePremium).mockResolvedValue({ planType: "PREMIUM", expiresAt: "2099-01-01T22:59:59Z" });
    const { user, invalidate } = renderSection("ACTIVE", { planType: "FREE", expiresAt: null });

    await user.click(screen.getByRole("button", { name: "Passer en Premium" }));
    await user.type(screen.getByLabelText("Premium jusqu'au"), "2099-01-01");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Espèces");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    await waitFor(() =>
      expect(activatePremium).toHaveBeenCalledWith(TENANT_ID, {
        expiresAt: "2099-01-01",
        amountXaf: 10000,
        method: "CASH",
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Plan Premium activé");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const keys = invalidate.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
    expect(keys).toEqual(
      expect.arrayContaining([
        JSON.stringify(["tenants"]),
        JSON.stringify(["tenant-detail", TENANT_ID]),
        JSON.stringify(["tenant-payments", TENANT_ID]),
        JSON.stringify(["plan-alerts"]),
      ]),
    );
  });

  it("keeps the dialog open with the server field error on a 422", async () => {
    vi.mocked(activatePremium).mockRejectedValue({
      response: {
        status: 422,
        data: {
          error: "Données invalides",
          domainCode: "VALIDATION_ERROR",
          details: { expiresAt: "La date d'expiration ne peut pas précéder l'échéance actuelle (20/11/2099)" },
        },
      },
    });
    const { user } = renderSection("ACTIVE", { planType: "PREMIUM", expiresAt: "2099-11-20T22:59:59Z" });

    await user.click(screen.getByRole("button", { name: "Passer en Premium" }));
    await user.type(screen.getByLabelText("Premium jusqu'au"), "2099-12-01");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Autre");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    expect(
      await screen.findByText("La date d'expiration ne peut pas précéder l'échéance actuelle (20/11/2099)"),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Données invalides, vérifiez le formulaire");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("suspends with a required reason and shows the error toast on 409", async () => {
    vi.mocked(suspendTenant).mockRejectedValue({
      response: { status: 409, data: { error: "Action impossible dans l'état actuel du compte", domainCode: "INVALID_STATE" } },
    });
    const { user } = renderSection("ACTIVE", { planType: "FREE", expiresAt: null });

    await user.click(screen.getByRole("button", { name: "Suspendre" }));
    await user.type(screen.getByLabelText("Motif"), "fraude");
    await user.click(screen.getAllByRole("button", { name: "Suspendre" }).at(-1)!);

    await waitFor(() => expect(suspendTenant).toHaveBeenCalledWith(TENANT_ID, "fraude"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Action impossible dans l'état actuel du compte");
  });

  it("downgrades to Free with an optional reason and reactivates", async () => {
    vi.mocked(downgradeToFree).mockResolvedValue();
    vi.mocked(reactivateTenant).mockResolvedValue();
    const { user, unmount } = renderSection("ACTIVE", { planType: "PREMIUM", expiresAt: "2099-01-01T22:59:59Z" });

    await user.click(screen.getByRole("button", { name: "Repasser en Gratuit" }));
    await user.click(screen.getAllByRole("button", { name: "Repasser en Gratuit" }).at(-1)!);
    await waitFor(() => expect(downgradeToFree).toHaveBeenCalledWith(TENANT_ID, ""));
    expect(await screen.findByRole("status")).toHaveTextContent("Compte repassé au plan Gratuit");
    unmount();

    const second = renderSection("SUSPENDED", { planType: "FREE", expiresAt: null });
    await second.user.click(screen.getByRole("button", { name: "Réactiver" }));
    await second.user.click(screen.getAllByRole("button", { name: "Réactiver" }).at(-1)!);
    await waitFor(() => expect(reactivateTenant).toHaveBeenCalledWith(TENANT_ID));
    expect(await screen.findByRole("status")).toHaveTextContent("Compte réactivé");
  });

  it("bean-validation 422 (English error) is shown in French", async () => {
    vi.mocked(activatePremium).mockRejectedValue({
      response: { status: 422, data: { error: "Validation failed", domainCode: "VALIDATION_ERROR", details: {} } },
    });
    const { user } = renderSection("ACTIVE", { planType: "FREE", expiresAt: null });

    await user.click(screen.getByRole("button", { name: "Passer en Premium" }));
    await user.type(screen.getByLabelText("Premium jusqu'au"), "2099-01-01");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Autre");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Données invalides, vérifiez le formulaire");
    expect(screen.queryByText(/Validation failed/)).not.toBeInTheDocument();
  });

  it("plan buttons are disabled when the subscription is unreadable or the tenant is being deleted", () => {
    const { unmount } = renderSection("ACTIVE", null);
    expect(screen.getByRole("button", { name: "Passer en Premium" })).toBeDisabled();
    expect(screen.getByText("Inconnu")).toBeInTheDocument();
    unmount();

    renderSection("DELETION_PENDING", { planType: "PREMIUM", expiresAt: "2099-01-01T22:59:59Z" });
    expect(screen.getByRole("button", { name: "Passer en Premium" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Repasser en Gratuit" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Suspendre" })).not.toBeInTheDocument();
  });
});
