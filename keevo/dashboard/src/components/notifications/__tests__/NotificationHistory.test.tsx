import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NotificationHistory } from "@/components/notifications/NotificationHistory";
import type { GlobalNotification } from "@/types/global-notification";

const rows: GlobalNotification[] = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    title: "Nouvelle fonctionnalité",
    body: "Exports PDF",
    target: "PREMIUM",
    targetTenantName: null,
    status: "SCHEDULED",
    scheduledAt: "2099-01-01T10:00:00Z",
    createdAt: "2026-10-13T10:00:00Z",
    sentAt: null,
    recipientCount: null,
    deliveredCount: null,
    failureCount: null,
    failureReason: null,
  },
  {
    id: "22222222-2222-2222-2222-222222222222",
    title: "Maintenance",
    body: "Ce soir",
    target: "TENANT",
    targetTenantName: "Boutique Awa",
    status: "SENT",
    scheduledAt: "2026-10-12T10:00:00Z",
    createdAt: "2026-10-12T10:00:00Z",
    sentAt: "2026-10-12T10:00:30Z",
    recipientCount: 5,
    deliveredCount: 4,
    failureCount: 1,
    failureReason: null,
  },
  {
    id: "33333333-3333-3333-3333-333333333333",
    title: "Panne",
    body: "Désolé",
    target: "ALL",
    targetTenantName: null,
    status: "FAILED",
    scheduledAt: "2026-10-11T10:00:00Z",
    createdAt: "2026-10-11T10:00:00Z",
    sentAt: null,
    recipientCount: 0,
    deliveredCount: 0,
    failureCount: 0,
    failureReason: "NO_RECIPIENT",
  },
];

describe("NotificationHistory (Story 9-5)", () => {
  it("lists target / status labels and delivered / failed counts, never ids or codes", () => {
    const { container } = render(
      <NotificationHistory notifications={rows} isLoading={false} onCancel={vi.fn()} pendingCancelIds={new Set()} />,
    );

    const [, scheduled, sent, failed] = screen.getAllByRole("row");
    expect(within(scheduled).getByText("Premium")).toBeInTheDocument();
    expect(within(scheduled).getByText("Programmé")).toBeInTheDocument();
    expect(within(sent).getByText("Boutique Awa")).toBeInTheDocument();
    expect(within(sent).getByText("Envoyé")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Appareils ciblés" })).toBeInTheDocument();
    expect(within(sent).getByText("5")).toBeInTheDocument();
    expect(within(sent).getByText("4")).toBeInTheDocument();
    expect(within(sent).getByText("1")).toBeInTheDocument();
    expect(within(failed).getByText("Tous")).toBeInTheDocument();
    expect(within(failed).getByText("Échec de l'envoi")).toBeInTheDocument();
    expect(within(failed).getByText("Aucun appareil ciblé")).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/1111|2222|SCHEDULED|PREMIUM|TENANT|NO_RECIPIENT/);
  });

  it("« Annuler » only on scheduled sends", async () => {
    const onCancel = vi.fn();
    render(
      <NotificationHistory notifications={rows} isLoading={false} onCancel={onCancel} pendingCancelIds={new Set()} />,
    );
    const buttons = screen.getAllByRole("button", { name: "Annuler" });
    expect(buttons).toHaveLength(1);
    await userEvent.click(buttons[0]);
    expect(onCancel).toHaveBeenCalledWith("11111111-1111-1111-1111-111111111111");
  });

  it("empty history", () => {
    render(<NotificationHistory notifications={[]} isLoading={false} onCancel={vi.fn()} pendingCancelIds={new Set()} />);
    expect(screen.getByText("Aucune annonce sur les 90 derniers jours")).toBeInTheDocument();
  });
});
