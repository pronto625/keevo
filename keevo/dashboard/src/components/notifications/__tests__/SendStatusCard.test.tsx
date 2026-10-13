import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SendStatusCard } from "@/components/notifications/SendStatusCard";
import type { GlobalNotification } from "@/types/global-notification";

const NOW = new Date("2026-10-13T10:05:00Z").getTime();

const base: GlobalNotification = {
  id: "3f2b1c4d-0000-4000-8000-0000000000aa",
  title: "Maintenance",
  body: "Ce soir",
  target: "ALL",
  targetTenantName: null,
  status: "SENDING",
  scheduledAt: "2026-10-13T10:00:00Z",
  createdAt: "2026-10-13T10:00:00Z",
  sentAt: null,
  recipientCount: null,
  deliveredCount: null,
  failureCount: null,
  failureReason: null,
};

describe("SendStatusCard (Story 9-5)", () => {
  it("in progress while not listed yet, sending or scheduled and due", () => {
    const { rerender } = render(<SendStatusCard notification={undefined} estimate={12} now={NOW} />);
    expect(screen.getByRole("status")).toHaveTextContent("Envoi en cours — 12 appareils ciblés");
    rerender(<SendStatusCard notification={base} estimate={1} now={NOW} />);
    expect(screen.getByRole("status")).toHaveTextContent("Envoi en cours — 1 appareil ciblé");
    rerender(<SendStatusCard notification={{ ...base, status: "SCHEDULED" }} estimate={1} now={NOW} />);
    expect(screen.getByRole("status")).toHaveTextContent("Envoi en cours — 1 appareil ciblé");
  });

  it("sent with delivered / failed counts", () => {
    render(
      <SendStatusCard
        notification={{ ...base, status: "SENT", recipientCount: 12, deliveredCount: 10, failureCount: 2 }}
        estimate={12}
        now={NOW}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("✅ Envoyé — 10 livrés / 2 échecs");
  });

  it("failed with its French reason, cancelled, never a raw code or id", () => {
    const { rerender } = render(
      <SendStatusCard notification={{ ...base, status: "FAILED", failureReason: "PUSH_UNAVAILABLE" }} estimate={3} now={NOW} />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("Échec de l'envoi — Notifications push non configurées");
    rerender(<SendStatusCard notification={{ ...base, status: "CANCELLED" }} estimate={3} now={NOW} />);
    expect(screen.getByRole("status")).toHaveTextContent("Envoi annulé");
    expect(screen.getByRole("status").textContent).not.toMatch(/CANCELLED|PUSH_UNAVAILABLE|3f2b1c4d/);
  });

  it("scheduled in the future", () => {
    render(
      <SendStatusCard
        notification={{ ...base, status: "SCHEDULED", scheduledAt: "2099-01-01T10:00:00Z" }}
        estimate={4}
        now={NOW}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(/^Programmé le .* — 4 appareils ciblés/);
  });

  it("still pending 15 min after the scheduled time: « Statut inconnu, actualisez »", () => {
    render(
      <SendStatusCard notification={base} estimate={4} now={new Date("2026-10-13T10:15:01Z").getTime()} />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("Statut inconnu, actualisez");
  });

  it("« Fermer » is always offered", async () => {
    const onDismiss = vi.fn();
    render(<SendStatusCard notification={undefined} estimate={1} now={NOW} onDismiss={onDismiss} />);
    await userEvent.click(screen.getByRole("button", { name: "Fermer" }));
    expect(onDismiss).toHaveBeenCalled();
  });
});
