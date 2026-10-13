import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { PlanSection } from "@/components/tenants/PlanSection";
import { addDays, todayWat } from "@/components/tenants/planDates";
import {
  activatePremium,
  downgradeToFree,
  getTenantPayments,
  reactivateTenant,
  suspendTenant,
  voidPayment,
} from "@/lib/api/admin-tenants";
import type { TenantPayment, TenantStatus, TenantSubscription, TenantSuspension } from "@/types/tenant";

vi.mock("@/lib/api/admin-tenants", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/admin-tenants")>();
  return {
    ...actual,
    activatePremium: vi.fn(),
    downgradeToFree: vi.fn(),
    suspendTenant: vi.fn(),
    reactivateTenant: vi.fn(),
    getTenantPayments: vi.fn(),
    voidPayment: vi.fn(),
  };
});

const TENANT_ID = "3f2b1c4d-0000-4000-8000-0000000000aa";
const ALL_CAPS = /\b[A-Z]{3,}(_[A-Z]+)*\b/;
/** Dates relative to today (WAT): the dialog refuses an expiry more than 3 years ahead. */
const TODAY = todayWat();
const IN_60 = addDays(TODAY, 60);
const IN_90 = addDays(TODAY, 90);
const IN_60_INSTANT = `${IN_60}T22:59:59Z`;

function renderSection(
  status: TenantStatus,
  subscription: TenantSubscription | null,
  extra: { suspension?: TenantSuspension | null; onRetryPlan?: () => void } = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(client, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const utils = render(
    <PlanSection tenantId={TENANT_ID} status={status} subscription={subscription} {...extra} />,
    { wrapper },
  );
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
    vi.mocked(activatePremium).mockResolvedValue({ planType: "PREMIUM", expiresAt: IN_60_INSTANT });
    const { user, invalidate } = renderSection("ACTIVE", { planType: "FREE", expiresAt: null });

    await user.click(screen.getByRole("button", { name: "Passer en Premium" }));
    await user.type(screen.getByLabelText("Premium jusqu'au"), IN_60);
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Espèces");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));
    await user.click(screen.getByRole("button", { name: "Confirmer l'activation" }));

    await waitFor(() =>
      expect(activatePremium).toHaveBeenCalledWith(TENANT_ID, {
        expiresAt: IN_60,
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
          details: { expiresAt: "La date d'expiration doit être postérieure à l'échéance actuelle" },
        },
      },
    });
    const { user } = renderSection("ACTIVE", { planType: "PREMIUM", expiresAt: IN_60_INSTANT });

    await user.click(screen.getByRole("button", { name: "Passer en Premium" }));
    await user.type(screen.getByLabelText("Premium jusqu'au"), IN_90);
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Autre");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));
    await user.click(screen.getByRole("button", { name: "Confirmer l'activation" }));

    expect(
      await screen.findByText("La date d'expiration doit être postérieure à l'échéance actuelle"),
    ).toBeInTheDocument();
    // back on the form, the general message inside the dialog (never a toast under the overlay)
    expect(screen.getByRole("dialog")).toContainElement(screen.getByRole("alert"));
    expect(screen.getByRole("alert")).toHaveTextContent("Données invalides, vérifiez le formulaire");
  });

  it("suspends with a required reason and shows the 409 error inside the dialog", async () => {
    vi.mocked(suspendTenant).mockRejectedValue({
      response: { status: 409, data: { error: "Action impossible dans l'état actuel du compte", domainCode: "INVALID_STATE" } },
    });
    const { user } = renderSection("ACTIVE", { planType: "FREE", expiresAt: null });

    await user.click(screen.getByRole("button", { name: "Suspendre" }));
    await user.type(screen.getByLabelText("Motif"), "fraude");
    await user.click(screen.getAllByRole("button", { name: "Suspendre" }).at(-1)!);

    await waitFor(() => expect(suspendTenant).toHaveBeenCalledWith(TENANT_ID, "fraude"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Action impossible dans l'état actuel du compte");
    expect(screen.getByRole("dialog")).toContainElement(screen.getByRole("alert"));
  });

  it("suspend: a 422 reason error is shown under the reason input; wording announces the forced logout", async () => {
    vi.mocked(suspendTenant).mockRejectedValue({
      response: {
        status: 422,
        data: { error: "Motif requis", domainCode: "VALIDATION_ERROR", details: { reason: "Motif requis (3 à 500 caractères)" } },
      },
    });
    const { user } = renderSection("ACTIVE", { planType: "FREE", expiresAt: null });

    await user.click(screen.getByRole("button", { name: "Suspendre" }));
    expect(screen.getByRole("dialog")).toHaveTextContent(
      "prend effet dans les minutes qui suivent (déconnexion forcée)",
    );
    await user.type(screen.getByLabelText("Motif"), "   abc   ");
    await user.click(screen.getAllByRole("button", { name: "Suspendre" }).at(-1)!);

    expect(await screen.findByText("Motif requis (3 à 500 caractères)")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("downgrade of a paid Premium shows its end date and warns the remaining paid days are lost", async () => {
    const { user } = renderSection("ACTIVE", { planType: "PREMIUM", expiresAt: "2027-01-31T22:59:59Z" });
    await user.click(screen.getByRole("button", { name: "Repasser en Gratuit" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("Premium payé jusqu'au 31/01/2027");
    expect(dialog).toHaveTextContent("les jours payés restants seront perdus");
  });

  it("reactivate: wording and a 409 error inside the dialog", async () => {
    vi.mocked(reactivateTenant).mockRejectedValue({
      response: { status: 409, data: { error: "Action impossible dans l'état actuel du compte", domainCode: "INVALID_STATE" } },
    });
    const { user } = renderSection("SUSPENDED", { planType: "FREE", expiresAt: null });
    await user.click(screen.getByRole("button", { name: "Réactiver" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("l'accès revient dans les minutes qui suivent");
    await user.click(screen.getAllByRole("button", { name: "Réactiver" }).at(-1)!);
    expect(await screen.findByRole("alert")).toHaveTextContent("Action impossible dans l'état actuel du compte");
    expect(screen.getByRole("dialog")).toContainElement(screen.getByRole("alert"));
  });

  it("shows the suspension date and reason of a suspended tenant", () => {
    renderSection("SUSPENDED", { planType: "FREE", expiresAt: null }, {
      suspension: { suspendedAt: "2026-10-12T09:00:00Z", reason: "Impayés" },
    });
    expect(screen.getByText("Suspendu le 12/10/2026 — motif : Impayés")).toBeInTheDocument();
  });

  it("voids a payment with a required reason, then refreshes the history", async () => {
    const payment: TenantPayment = {
      id: "3f2b1c4d-0000-4000-8000-0000000000bb", paidAt: "2026-10-13T09:00:00Z", amountXaf: 10000,
      method: "CASH", periodStart: "2026-10-13", periodEnd: "2026-11-12", note: null, recordedByLabel: "Super admin",
    };
    vi.mocked(getTenantPayments).mockResolvedValue([payment]);
    vi.mocked(voidPayment).mockResolvedValue();
    const { user, invalidate } = renderSection("ACTIVE", { planType: "PREMIUM", expiresAt: "2026-11-12T22:59:59Z" });

    await user.click(await screen.findByRole("button", { name: "Annuler ce paiement" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("13/10/2026 → 12/11/2026");
    await user.type(screen.getByLabelText("Motif"), "Erreur de saisie");
    await user.click(screen.getAllByRole("button", { name: "Annuler ce paiement" }).at(-1)!);

    await waitFor(() => expect(voidPayment).toHaveBeenCalledWith(TENANT_ID, payment.id, "Erreur de saisie"));
    expect(await screen.findByRole("status")).toHaveTextContent("Paiement annulé");
    expect(invalidate.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey))).toContain(
      JSON.stringify(["tenant-payments", TENANT_ID]),
    );
  });

  it("downgrades to Free with an optional reason and reactivates", async () => {
    vi.mocked(downgradeToFree).mockResolvedValue();
    vi.mocked(reactivateTenant).mockResolvedValue();
    const { user, unmount } = renderSection("ACTIVE", { planType: "PREMIUM", expiresAt: IN_60_INSTANT });

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
    await user.type(screen.getByLabelText("Premium jusqu'au"), IN_60);
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Autre");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));
    await user.click(screen.getByRole("button", { name: "Confirmer l'activation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Données invalides, vérifiez le formulaire");
    expect(screen.queryByText(/Validation failed/)).not.toBeInTheDocument();
  });

  it("plan buttons are disabled when the subscription is unreadable (with « Réessayer ») or the tenant is being deleted", async () => {
    const onRetryPlan = vi.fn();
    const { unmount, user } = renderSection("ACTIVE", null, { onRetryPlan });
    expect(screen.getByRole("button", { name: "Passer en Premium" })).toBeDisabled();
    expect(screen.getByText("Plan illisible pour le moment")).toBeInTheDocument();
    expect(screen.queryByText("Inconnu")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetryPlan).toHaveBeenCalled();
    unmount();

    renderSection("DELETION_PENDING", { planType: "PREMIUM", expiresAt: IN_60_INSTANT });
    expect(screen.getByRole("button", { name: "Passer en Premium" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Repasser en Gratuit" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Suspendre" })).not.toBeInTheDocument();
  });
});
