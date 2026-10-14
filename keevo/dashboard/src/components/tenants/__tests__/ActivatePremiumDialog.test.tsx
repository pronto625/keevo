import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ActivatePremiumDialog, coveredFrom, minExpiryDate } from "@/components/tenants/ActivatePremiumDialog";
import type { TenantPayment, TenantSubscription } from "@/types/tenant";

const TODAY = "2026-10-13";
const NO_ERRORS: Record<string, string> = {};

function setup(
  serverErrors: Record<string, string> = NO_ERRORS,
  currentSubscription: TenantSubscription | null = null,
  serverError: string | null = null,
) {
  const onSubmit = vi.fn();
  const onCancel = vi.fn();
  render(
    <ActivatePremiumDialog
      isPending={false}
      serverErrors={serverErrors}
      serverError={serverError}
      currentSubscription={currentSubscription}
      onSubmit={onSubmit}
      onCancel={onCancel}
      today={TODAY}
    />,
  );
  return { onSubmit, onCancel, user: userEvent.setup() };
}

describe("ActivatePremiumDialog", () => {
  it("lists payment methods with French labels only", () => {
    setup();
    const options = Array.from(
      (screen.getByLabelText("Moyen de paiement") as HTMLSelectElement).options,
    ).map((o) => o.textContent);
    expect(options).toEqual(["Choisir…", "Mobile Money", "Espèces", "Virement bancaire", "Autre"]);
    expect(document.body.textContent).not.toMatch(/MOBILE_MONEY|BANK_TRANSFER/);
  });

  it("shows field errors and does not submit an empty or invalid form", async () => {
    const { onSubmit, user } = setup();
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    expect(screen.getByText("Date d'expiration requise")).toBeInTheDocument();
    expect(screen.getByText("Montant requis")).toBeInTheDocument();
    expect(screen.getByText("Moyen de paiement requis")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Premium jusqu'au"), TODAY);
    await user.type(screen.getByLabelText("Montant (XAF)"), "0");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    expect(screen.getByText("La date doit être postérieure à aujourd'hui")).toBeInTheDocument();
    expect(screen.getByText("Le montant doit être un entier supérieur à 0")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("submits the expiry date, the amount, the method and the trimmed note", async () => {
    const { onSubmit, user } = setup();
    await user.type(screen.getByLabelText("Premium jusqu'au"), "2026-11-12");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Mobile Money");
    await user.type(screen.getByLabelText("Note (facultatif)"), "  réf OM 42 ");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));
    expect(onSubmit).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Confirmer l'activation" }));

    expect(onSubmit).toHaveBeenCalledWith({
      expiresAt: "2026-11-12",
      amountXaf: 10000,
      method: "MOBILE_MONEY",
      note: "réf OM 42",
    });
  });

  it("displays server field errors", () => {
    setup({ expiresAt: "La date d'expiration ne peut pas précéder l'échéance actuelle (20/11/2026)" });
    expect(
      screen.getByText("La date d'expiration ne peut pas précéder l'échéance actuelle (20/11/2026)"),
    ).toBeInTheDocument();
  });

  it("cancels", async () => {
    const { onCancel, user } = setup();
    await user.click(screen.getByRole("button", { name: "Annuler" }));
    expect(onCancel).toHaveBeenCalled();
  });

  it("minimum expiry: tomorrow, or the day after a running Premium's expiry (WAT)", () => {
    expect(minExpiryDate(TODAY, null)).toBe("2026-10-14");
    expect(minExpiryDate(TODAY, { planType: "FREE", expiresAt: null })).toBe("2026-10-14");
    expect(minExpiryDate(TODAY, { planType: "PREMIUM_TRIAL", expiresAt: "2026-12-01T22:59:59Z" })).toBe("2026-10-14");
    expect(minExpiryDate(TODAY, { planType: "PREMIUM", expiresAt: "2026-11-20T22:59:59Z" })).toBe("2026-11-21");
    expect(minExpiryDate(TODAY, { planType: "PREMIUM", expiresAt: "2026-10-01T22:59:59Z" })).toBe("2026-10-14");
  });

  it("refuses an expiry not after the current Premium expiry", async () => {
    const { onSubmit, user } = setup(NO_ERRORS, { planType: "PREMIUM", expiresAt: "2026-11-20T22:59:59Z" });
    expect(screen.getByLabelText("Premium jusqu'au")).toHaveAttribute("min", "2026-11-21");
    await user.type(screen.getByLabelText("Premium jusqu'au"), "2026-11-20");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Espèces");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    expect(screen.getByText("La date doit être au plus tôt le 21/11/2026")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("payment date: defaults to today (not sent), a past date is sent as paidAt, a future one is refused", async () => {
    const { onSubmit, user } = setup();
    const paidAt = screen.getByLabelText("Date du paiement");
    expect(paidAt).toHaveValue(TODAY);
    expect(paidAt).toHaveAttribute("max", TODAY);

    await user.type(screen.getByLabelText("Premium jusqu'au"), "2026-11-12");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Espèces");
    await user.clear(paidAt);
    await user.type(paidAt, "2026-10-20");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));
    expect(screen.getByText("La date de paiement ne peut pas être dans le futur")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();

    await user.clear(paidAt);
    await user.type(paidAt, "2026-10-10");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));
    await user.click(screen.getByRole("button", { name: "Confirmer l'activation" }));
    expect(onSubmit).toHaveBeenCalledWith({
      expiresAt: "2026-11-12",
      amountXaf: 10000,
      method: "CASH",
      paidAt: "2026-10-10",
    });
  });

  it("clears a field's server error once the user edits that field", async () => {
    const { user } = setup({ amountXaf: "Montant trop élevé", expiresAt: "Date refusée" });
    expect(screen.getByText("Montant trop élevé")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Montant (XAF)"), "5");

    expect(screen.queryByText("Montant trop élevé")).not.toBeInTheDocument();
    expect(screen.getByText("Date refusée")).toBeInTheDocument();
  });

  it("confirmation step summarises amount, method, payment date and covered period; « Modifier » goes back", async () => {
    const { onSubmit, user } = setup(NO_ERRORS, { planType: "PREMIUM", expiresAt: "2026-11-20T22:59:59Z" });
    await user.type(screen.getByLabelText("Premium jusqu'au"), "2026-12-20");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Espèces");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("Confirmer l'activation Premium");
    expect(dialog.textContent).toMatch(/10\s000/);
    expect(dialog).toHaveTextContent("Espèces");
    expect(dialog).toHaveTextContent("13/10/2026");
    // renewal: covered from the day after the current Premium end
    expect(dialog).toHaveTextContent("21/11/2026 → 20/12/2026");
    expect(dialog.textContent ?? "").not.toMatch(/CASH|PREMIUM/);

    await user.click(screen.getByRole("button", { name: "Modifier" }));
    expect(screen.getByLabelText("Montant (XAF)")).toHaveValue(10000);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("refuses an expiry more than 3 years after today", async () => {
    const { onSubmit, user } = setup();
    expect(screen.getByLabelText("Premium jusqu'au")).toHaveAttribute("max", "2029-10-13");
    await user.type(screen.getByLabelText("Premium jusqu'au"), "2029-10-14");
    await user.type(screen.getByLabelText("Montant (XAF)"), "10000");
    await user.selectOptions(screen.getByLabelText("Moyen de paiement"), "Espèces");
    await user.click(screen.getByRole("button", { name: "Activer Premium" }));

    expect(screen.getByText("Date d'expiration trop lointaine")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("shows a general server error at the top of the dialog", () => {
    setup(NO_ERRORS, null, "Action impossible dans l'état actuel du compte");
    expect(screen.getByRole("dialog")).toContainElement(screen.getByRole("alert"));
    expect(screen.getByRole("alert")).toHaveTextContent("Action impossible dans l'état actuel du compte");
  });

  it("an expiry granted by a since-voided payment does not raise the minimum date", () => {
    const pay = (periodEnd: string, voidedAt: string | null): TenantPayment => ({
      id: periodEnd, paidAt: "2026-10-01T09:00:00Z", amountXaf: 10000, method: "CASH",
      periodStart: "2026-10-01", periodEnd, note: null, recordedByLabel: "Super admin", voidedAt,
    });
    const typo: TenantSubscription = { planType: "PREMIUM", expiresAt: "2036-10-31T22:59:59Z" };

    expect(minExpiryDate(TODAY, typo, [pay("2036-10-31", "2026-10-12T09:00:00Z"), pay("2026-10-31", null)]))
      .toBe("2026-11-01");
    expect(coveredFrom(TODAY, typo, [pay("2036-10-31", "2026-10-12T09:00:00Z")])).toBe(TODAY);
    // the voided payment is not the one that granted the expiry: unchanged
    expect(minExpiryDate(TODAY, typo, [pay("2026-10-31", "2026-10-12T09:00:00Z")])).toBe("2036-11-01");
  });
});
