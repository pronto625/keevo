import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import NotificationsPage from "@/app/notifications/page";
import {
  cancelGlobalNotification,
  getGlobalNotifications,
  getRecipientEstimate,
  scheduleGlobalNotification,
  searchPushTenants,
} from "@/lib/api/admin-notifications";
import type { GlobalNotification } from "@/types/global-notification";

vi.mock("@/lib/api/admin-notifications", () => ({
  cancelGlobalNotification: vi.fn(),
  getGlobalNotifications: vi.fn(),
  getRecipientEstimate: vi.fn(),
  scheduleGlobalNotification: vi.fn(),
  searchPushTenants: vi.fn(),
}));
vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const history = vi.mocked(getGlobalNotifications);
const schedule = vi.mocked(scheduleGlobalNotification);
const cancel = vi.mocked(cancelGlobalNotification);
const ID = "3f2b1c4d-0000-4000-8000-0000000000aa";

function row(status: string, extra: Partial<GlobalNotification> = {}): GlobalNotification {
  return {
    id: ID,
    title: "Maintenance",
    body: "Ce soir",
    target: "ALL",
    targetTenantName: null,
    status,
    scheduledAt: new Date().toISOString(),
    createdAt: new Date().toISOString(),
    sentAt: null,
    recipientCount: null,
    deliveredCount: null,
    failureCount: null,
    failureReason: null,
    ...extra,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <NotificationsPage />
    </QueryClientProvider>,
  );
}

describe("« Notifications » page (Story 9-5 — global push)", () => {
  beforeEach(() => {
    history.mockReset().mockResolvedValue([]);
    schedule.mockReset();
    cancel.mockReset().mockResolvedValue(undefined);
    vi.mocked(getRecipientEstimate).mockReset().mockResolvedValue(5);
    vi.mocked(searchPushTenants).mockReset().mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("polls the status every 10 s until sent, then stops", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    schedule.mockResolvedValue({ id: ID, recipientEstimate: 5 });
    renderPage();
    await screen.findByText("Aucune annonce sur les 90 derniers jours");

    history.mockResolvedValue([row("SENDING")]);
    await user.type(screen.getByLabelText("Titre"), "Maintenance");
    await user.type(screen.getByLabelText("Message"), "Ce soir");
    await screen.findByText("5 appareils ciblés");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirmer l'envoi" }));

    expect(await screen.findByRole("status")).toHaveTextContent("Envoi en cours — 5 appareils ciblés");
    const afterSubmit = history.mock.calls.length;

    history.mockResolvedValue([row("SENT", { recipientCount: 5, deliveredCount: 4, failureCount: 1 })]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("✅ Envoyé — 4 livrés / 1 échecs"));
    const afterSent = history.mock.calls.length;
    expect(afterSent).toBeGreaterThan(afterSubmit);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(history.mock.calls.length).toBe(afterSent);
  });

  it("a send still pending 15 min after its time stops polling: « Statut inconnu, actualisez »", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    schedule.mockResolvedValue({ id: ID, recipientEstimate: 5 });
    history.mockResolvedValue([row("SENDING")]);
    renderPage();

    await user.type(await screen.findByLabelText("Titre"), "Maintenance");
    await user.type(screen.getByLabelText("Message"), "Ce soir");
    await screen.findByText("5 appareils ciblés");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirmer l'envoi" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Envoi en cours");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(16 * 60_000);
    });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Statut inconnu, actualisez"));
    const calls = history.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(history.mock.calls.length).toBe(calls);
  });

  it("FCM not configured: « Échec de l'envoi »", async () => {
    schedule.mockResolvedValue({ id: ID, recipientEstimate: 5 });
    history.mockResolvedValue([
      row("FAILED", { recipientCount: 0, deliveredCount: 0, failureCount: 0, failureReason: "PUSH_UNAVAILABLE" }),
    ]);
    const user = userEvent.setup();
    renderPage();

    await user.type(await screen.findByLabelText("Titre"), "Maintenance");
    await user.type(screen.getByLabelText("Message"), "Ce soir");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirmer l'envoi" }));

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("Échec de l'envoi — Notifications push non configurées"),
    );
  });

  it("cancels a scheduled send from the history", async () => {
    history.mockResolvedValue([row("SCHEDULED", { scheduledAt: "2099-01-01T10:00:00Z" })]);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Annuler" }));
    expect(cancel).toHaveBeenCalledWith(ID);
  });

  it("first load error then retry", async () => {
    history.mockRejectedValueOnce(new Error("down"));
    renderPage();
    expect(await screen.findByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(await screen.findByText("Aucune annonce sur les 90 derniers jours")).toBeInTheDocument();
  });
});
