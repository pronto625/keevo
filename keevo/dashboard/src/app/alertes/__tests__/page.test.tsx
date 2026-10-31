import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import AlertesPageClient from "@/app/alertes/AlertesPageClient";
import { getAlerts, markAlertHandled } from "@/lib/api/admin-alerts";
import type { AdminAlert } from "@/types/alerts";

vi.mock("@/lib/api/admin-alerts", () => ({ getAlerts: vi.fn(), markAlertHandled: vi.fn() }));
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const list = vi.mocked(getAlerts);
const handle = vi.mocked(markAlertHandled);

const FEEDBACK_KEY = "feedback:3f2b1c4d-0000-4000-8000-0000000000aa";
const ALERTS: AdminAlert[] = [
  {
    key: FEEDBACK_KEY,
    severity: "HIGH",
    title: "Retour utilisateur prioritaire",
    description: "Boutique Awa — Problème signalé : « Le scanner plante »",
    occurredAt: "2026-10-12T09:00:00Z",
    handled: false,
    handledAt: null,
  },
  {
    key: "deletion:11111111-1111-1111-1111-111111111111:2026-10-25",
    severity: "MEDIUM",
    title: "Suppression de compte demandée",
    description: "Boutique Ndiaye — suppression définitive dans 12 jours (le 25/10/2026)",
    occurredAt: "2026-10-10T09:00:00Z",
    handled: false,
    handledAt: null,
  },
  {
    key: "plan-expiring:22222222-2222-2222-2222-222222222222:2026-10-16",
    severity: "LOW",
    title: "Abonnement bientôt expiré",
    description: "Boutique Kane — abonnement Premium expire dans 3 jours (le 16/10/2026)",
    occurredAt: "2026-10-09T09:00:00Z",
    handled: false,
    handledAt: null,
  },
];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AlertesPageClient />
    </QueryClientProvider>,
  );
}

describe("« Alertes » page (Story 9-5)", () => {
  beforeEach(() => {
    list.mockReset().mockResolvedValue(ALERTS);
    handle.mockReset().mockResolvedValue(undefined);
  });

  it("lists unhandled alerts with French severity labels and no ids / codes", async () => {
    renderPage();

    expect(await screen.findByText("Retour utilisateur prioritaire")).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith(false);
    expect(screen.getByText("Haute")).toBeInTheDocument();
    expect(screen.getByText("Moyenne")).toBeInTheDocument();
    expect(screen.getByText("Basse")).toBeInTheDocument();
    const text = document.body.textContent ?? "";
    expect(text).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}/);
    expect(text).not.toContain("feedback:");
    expect(text).not.toMatch(/\b(HIGH|MEDIUM|LOW)\b/);
    expect(text.toLowerCase()).not.toContain("tenant");
  });

  it("« Marquer comme traité » sends the key and reloads the feed (the alert disappears)", async () => {
    renderPage();
    const item = (await screen.findByText("Retour utilisateur prioritaire")).closest("li") as HTMLElement;
    list.mockResolvedValue(ALERTS.slice(1));

    await userEvent.click(within(item).getByRole("button", { name: "Marquer comme traité" }));

    await waitFor(() => expect(handle).toHaveBeenCalledWith(FEEDBACK_KEY));
    await waitFor(() => expect(screen.queryByText("Retour utilisateur prioritaire")).not.toBeInTheDocument());
  });

  it("toggle « Afficher les alertes traitées » lists handled alerts with their date, without the action", async () => {
    renderPage();
    await screen.findByText("Retour utilisateur prioritaire");
    list.mockResolvedValue([{ ...ALERTS[0], handled: true, handledAt: "2026-10-13T08:00:00Z" }]);

    await userEvent.click(screen.getByLabelText("Afficher les alertes traitées"));

    await waitFor(() => expect(list).toHaveBeenLastCalledWith(true));
    expect(await screen.findByText(/Traitée le 13\/10\/2026/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Marquer comme traité" })).not.toBeInTheDocument();
  });

  it("failed action shows an error", async () => {
    handle.mockReset().mockRejectedValueOnce(new Error("boom"));
    renderPage();
    const item = (await screen.findByText("Retour utilisateur prioritaire")).closest("li") as HTMLElement;
    await userEvent.click(within(item).getByRole("button", { name: "Marquer comme traité" }));
    expect(await screen.findByText(/L'action n'a pas pu être enregistrée/)).toBeInTheDocument();
  });

  it("empty feed", async () => {
    list.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText(/Aucune alerte en cours/)).toBeInTheDocument();
  });

  it("first load error: « Erreur de chargement » + « Réessayer »", async () => {
    list.mockReset().mockRejectedValueOnce(new Error("boom")).mockResolvedValue(ALERTS);
    renderPage();
    expect(await screen.findByText("Erreur de chargement")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Réessayer" })).toBeInTheDocument();
  });
});
