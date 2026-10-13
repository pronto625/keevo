import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PlanAlertsBanner } from "@/components/tenants/PlanAlertsBanner";
import type { PlanAlert } from "@/types/tenant";

const A = "3f2b1c4d-0000-4000-8000-00000000000a";
const B = "3f2b1c4d-0000-4000-8000-00000000000b";

const ALERTS: PlanAlert[] = [
  { tenantId: A, tenantName: "Boutique Awa", kind: "EXPIRING", plan: "PREMIUM", daysLeft: 3,
    expiresAt: "2026-10-16T22:59:59Z", occurredAt: null },
  { tenantId: A, tenantName: "Boutique Awa", kind: "EXPIRING", plan: "PREMIUM_TRIAL", daysLeft: 1,
    expiresAt: "2026-10-14T22:59:59Z", occurredAt: null },
  { tenantId: B, tenantName: null, kind: "EXPIRED", plan: "PREMIUM", daysLeft: null,
    expiresAt: null, occurredAt: "2026-10-12T01:00:00Z" },
];

describe("PlanAlertsBanner", () => {
  it("lists expiring and expired plans by business name, no ids or codes", () => {
    const { container } = render(<PlanAlertsBanner alerts={ALERTS} />);

    expect(screen.getByText("⚠️ Boutique Awa — plan Premium expire dans 3 jours")).toBeInTheDocument();
    expect(screen.getByText("⚠️ Boutique Awa — plan Essai Premium expire dans 1 jour")).toBeInTheDocument();
    expect(
      screen.getByText("⚠️ Sans nom — plan Premium expiré, repassé en Gratuit le 12/10/2026"),
    ).toBeInTheDocument();
    const text = container.textContent ?? "";
    expect(text).not.toContain(A);
    expect(text).not.toMatch(/PREMIUM|EXPIRING|EXPIRED/);
  });

  it("opens the tenant on click", async () => {
    const onSelect = vi.fn();
    render(<PlanAlertsBanner alerts={ALERTS} onSelect={onSelect} />);
    await userEvent.setup().click(screen.getByText("⚠️ Boutique Awa — plan Premium expire dans 3 jours"));
    expect(onSelect).toHaveBeenCalledWith(A);
  });

  it("shows an unavailable state with « Réessayer » when the alerts query failed", async () => {
    const onRetry = vi.fn();
    render(<PlanAlertsBanner alerts={undefined} isError onRetry={onRetry} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Alertes d'abonnement indisponibles");
    await userEvent.setup().click(screen.getByRole("button", { name: "Réessayer" }));
    expect(onRetry).toHaveBeenCalled();
  });

  it("shows 5 alerts, then « Voir les N autres » toggles the rest", async () => {
    const many: PlanAlert[] = Array.from({ length: 7 }, (_, i) => ({
      tenantId: A, tenantName: `Boutique ${i + 1}`, kind: "EXPIRING", plan: "PREMIUM", daysLeft: i + 1,
      expiresAt: null, occurredAt: null,
    }));
    const user = userEvent.setup();
    render(<PlanAlertsBanner alerts={many} />);

    expect(screen.getByText(/Boutique 5 —/)).toBeInTheDocument();
    expect(screen.queryByText(/Boutique 6 —/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Voir les 2 autres" }));
    expect(screen.getByText(/Boutique 7 —/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Voir moins" }));
    expect(screen.queryByText(/Boutique 6 —/)).not.toBeInTheDocument();
  });

  it("no toggle with 5 alerts or fewer", () => {
    render(<PlanAlertsBanner alerts={ALERTS} />);
    expect(screen.queryByRole("button", { name: /Voir/ })).not.toBeInTheDocument();
  });

  it("renders nothing without alerts", () => {
    const { container } = render(<PlanAlertsBanner alerts={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});
