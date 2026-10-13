import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { InactiveTenantsSection } from "@/components/operations/InactiveTenantsSection";
import type { InactiveTenant } from "@/types/operations";

const TENANT_A = "3f2b1c4d-0000-4000-8000-00000000000a";
const TENANT_B = "3f2b1c4d-0000-4000-8000-00000000000b";

const DATA: InactiveTenant[] = [
  { tenantId: TENANT_B, tenantName: null, plan: "FREE", lastActivityAt: null, daysSinceLastActivity: null },
  {
    tenantId: TENANT_A,
    tenantName: "Boutique Dormante",
    plan: "PREMIUM",
    lastActivityAt: "2026-09-28T08:30:00Z",
    daysSinceLastActivity: 15,
  },
];

describe("InactiveTenantsSection", () => {
  it("shows « Jamais » for a tenant never active and the days since last activity", () => {
    const { container } = render(<InactiveTenantsSection data={DATA} />);

    expect(screen.getByText("Jamais")).toBeInTheDocument();
    expect(screen.getByText("Jamais active")).toBeInTheDocument();
    expect(screen.getByText("Sans nom")).toBeInTheDocument();
    expect(screen.getByText("Boutique Dormante")).toBeInTheDocument();
    expect(screen.getByText("15 jours")).toBeInTheDocument();
    expect(screen.getByText("28/09/2026")).toBeInTheDocument();
    expect(screen.getByText("Premium")).toBeInTheDocument();
    expect(screen.getByText("Gratuit")).toBeInTheDocument();
    expect(screen.getByText("(2)")).toBeInTheDocument();

    const text = container.textContent ?? "";
    expect(text).not.toContain(TENANT_A);
    expect(text).not.toContain(TENANT_B);
  });

  it("formats the last activity date in Africa/Douala, not the browser time zone", () => {
    render(
      <InactiveTenantsSection
        data={[
          {
            tenantId: TENANT_A,
            tenantName: "Boutique Tardive",
            plan: "FREE",
            // 23:30 UTC = 00:30 the next day in Douala (UTC+1).
            lastActivityAt: "2026-09-28T23:30:00Z",
            daysSinceLastActivity: 15,
          },
        ]}
      />,
    );
    expect(screen.getByText("29/09/2026")).toBeInTheDocument();
  });

  it("shows the empty state", () => {
    render(<InactiveTenantsSection data={[]} />);
    expect(screen.getByText("Aucune entreprise inactive")).toBeInTheDocument();
  });

  it("shows a skeleton before data arrives", () => {
    render(<InactiveTenantsSection data={undefined} />);
    expect(screen.getByTestId("inactive-skeleton")).toBeInTheDocument();
  });

  it("keeps cached tenants and warns when a refresh fails", () => {
    render(<InactiveTenantsSection data={DATA} isError onRetry={() => {}} />);
    expect(screen.getByText("Boutique Dormante")).toBeInTheDocument();
    expect(screen.getByText("Échec de l'actualisation — données possiblement obsolètes")).toBeInTheDocument();
  });

  it("shows a load error on first-load failure", () => {
    render(<InactiveTenantsSection data={undefined} isError />);
    expect(screen.getByText("Erreur de chargement")).toBeInTheDocument();
  });
});
