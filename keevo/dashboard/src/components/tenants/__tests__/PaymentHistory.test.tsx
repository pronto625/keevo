import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { PaymentHistory } from "@/components/tenants/PaymentHistory";
import type { TenantPayment } from "@/types/tenant";

const PAYMENTS: TenantPayment[] = [
  {
    paidAt: "2026-10-13T09:00:00Z",
    amountXaf: 10000,
    method: "MOBILE_MONEY",
    periodStart: "2026-10-13",
    periodEnd: "2026-11-12",
    note: "réf OM 42",
    recordedByLabel: "Super admin",
  },
  {
    paidAt: "2026-09-01T23:30:00Z",
    amountXaf: 5000,
    method: "BANK_TRANSFER",
    periodStart: "2026-09-02",
    periodEnd: "2026-10-01",
    note: null,
    recordedByLabel: "Super admin",
  },
];

describe("PaymentHistory", () => {
  it("renders payments with French labels, WAT dates and periods, no raw codes", () => {
    const { container } = render(<PaymentHistory payments={PAYMENTS} isLoading={false} isError={false} />);

    expect(screen.getByText("Mobile Money")).toBeInTheDocument();
    expect(screen.getByText("Virement bancaire")).toBeInTheDocument();
    expect(screen.getByText("13/10/2026 → 12/11/2026", { exact: false })).toBeInTheDocument();
    // 23:30 UTC on 01/09 is already 02/09 in WAT
    expect(screen.getByText("02/09/2026")).toBeInTheDocument();
    expect(screen.getByText("réf OM 42")).toBeInTheDocument();
    expect(screen.getAllByText("Super admin")).toHaveLength(2);

    const text = container.textContent ?? "";
    expect(text).not.toMatch(/MOBILE_MONEY|BANK_TRANSFER/);
    expect(text).toMatch(/10\s000/);
  });

  it("shows the empty, loading and error states", () => {
    const { rerender } = render(<PaymentHistory payments={[]} isLoading={false} isError={false} />);
    expect(screen.getByText("Aucun paiement enregistré")).toBeInTheDocument();
    rerender(<PaymentHistory payments={undefined} isLoading isError={false} />);
    expect(screen.getByLabelText("Chargement des paiements")).toBeInTheDocument();
    rerender(<PaymentHistory payments={undefined} isLoading={false} isError />);
    expect(screen.getByText("Erreur de chargement des paiements")).toBeInTheDocument();
  });
});
