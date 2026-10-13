"use client";

import { useState } from "react";
import { formatXAFFull } from "@/lib/format";
import { PAYMENT_METHODS, paymentMethodLabel } from "@/lib/labels";
import type { ActivatePremiumInput, PaymentMethod, TenantPayment, TenantSubscription } from "@/types/tenant";
import { addDays, addYears, formatIsoDate, isoDateWat, todayWat } from "./planDates";

interface ActivatePremiumDialogProps {
  isPending: boolean;
  /** Field errors returned by the server (422 VALIDATION_ERROR details). */
  serverErrors?: Record<string, string>;
  /** General (non-field) error of the last server answer, shown at the top of the dialog. */
  serverError?: string | null;
  /** Current subscription: a running Premium can only be extended past its expiry. */
  currentSubscription?: TenantSubscription | null;
  /** Payment history: an expiry granted by a since-voided payment does not count. */
  payments?: TenantPayment[];
  onSubmit: (input: ActivatePremiumInput) => void;
  onCancel: () => void;
  /** Injectable for tests; defaults to today in WAT. */
  today?: string;
}

type Field = "expiresAt" | "amountXaf" | "method" | "paidAt" | "note";
type FieldErrors = Partial<Record<Field, string>>;

const NO_SERVER_ERRORS: Record<string, string> = {};
const NO_PAYMENTS: TenantPayment[] = [];

/** Farthest expiry accepted (the server refuses more than 3 years after today). */
export const MAX_EXPIRY_YEARS = 3;

/**
 * End of the running Premium (yyyy-MM-dd), as the server computes it for a renewal: the
 * subscription expiry, unless it was granted by a since-voided payment that no counted payment
 * covers — then the latest end among the counted payments (null when none, or not Premium).
 */
export function currentPremiumEnd(
  current: TenantSubscription | null | undefined,
  payments: TenantPayment[] = NO_PAYMENTS,
): string | null {
  if (current?.planType !== "PREMIUM") return null;
  const end = isoDateWat(current.expiresAt);
  if (!end) return null;
  const counted = payments.filter((p) => !p.voidedAt);
  const grantedByVoided = payments.some((p) => p.voidedAt && p.periodEnd === end);
  const covered = counted.some((p) => p.periodEnd >= end);
  if (!grantedByVoided || covered) return end;
  return counted.reduce<string | null>((max, p) => (max === null || p.periodEnd > max ? p.periodEnd : max), null);
}

/** Earliest selectable expiry: tomorrow, or the day after a running Premium's expiry. */
export function minExpiryDate(
  today: string,
  current: TenantSubscription | null | undefined,
  payments: TenantPayment[] = NO_PAYMENTS,
): string {
  const tomorrow = addDays(today, 1);
  const currentEnd = currentPremiumEnd(current, payments);
  if (!currentEnd) return tomorrow;
  const afterCurrent = addDays(currentEnd, 1);
  return afterCurrent > tomorrow ? afterCurrent : tomorrow;
}

/** First day covered by the new payment: today, or the day after a running Premium's end. */
export function coveredFrom(
  today: string,
  current: TenantSubscription | null | undefined,
  payments: TenantPayment[] = NO_PAYMENTS,
): string {
  const currentEnd = currentPremiumEnd(current, payments);
  return currentEnd && currentEnd >= today ? addDays(currentEnd, 1) : today;
}

const inputClass =
  "w-full border border-gray-300 rounded-lg bg-white text-gray-900 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500";
const labelClass = "block text-xs font-medium text-gray-600 mb-1";

/**
 * Modal « Passer en Premium »: expiry date, amount (XAF), payment method, note (Story 9-2), then a
 * confirmation step summarising amount, method, payment date and covered period before submit.
 */
export function ActivatePremiumDialog({
  isPending,
  serverErrors = NO_SERVER_ERRORS,
  serverError = null,
  currentSubscription = null,
  payments = NO_PAYMENTS,
  onSubmit,
  onCancel,
  today = todayWat(),
}: ActivatePremiumDialogProps) {
  const minDate = minExpiryDate(today, currentSubscription, payments);
  const maxDate = addYears(today, MAX_EXPIRY_YEARS);
  const [confirming, setConfirming] = useState(false);
  const [expiresAt, setExpiresAt] = useState("");
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState<PaymentMethod | "">("");
  const [paidAt, setPaidAt] = useState(today);
  const [note, setNote] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  // Server errors of a field are hidden once the user edits that field (reset on a new answer).
  const [prevServerErrors, setPrevServerErrors] = useState(serverErrors);
  const [editedFields, setEditedFields] = useState<ReadonlySet<string>>(new Set());
  const [prevServerError, setPrevServerError] = useState(serverError);
  if (prevServerErrors !== serverErrors || prevServerError !== serverError) {
    setPrevServerErrors(serverErrors);
    setPrevServerError(serverError);
    setEditedFields(new Set());
    // a new failed answer: back to the form so its errors are visible
    if (serverError || Object.keys(serverErrors).length > 0) setConfirming(false);
  }
  const edited = (field: Field) => {
    if (!editedFields.has(field)) setEditedFields(new Set(editedFields).add(field));
  };

  const validate = (): FieldErrors => {
    const e: FieldErrors = {};
    if (!expiresAt) e.expiresAt = "Date d'expiration requise";
    else if (expiresAt <= today) e.expiresAt = "La date doit être postérieure à aujourd'hui";
    else if (expiresAt < minDate) e.expiresAt = `La date doit être au plus tôt le ${formatIsoDate(minDate)}`;
    else if (expiresAt > maxDate) e.expiresAt = "Date d'expiration trop lointaine";
    const n = Number(amount);
    if (!amount.trim()) e.amountXaf = "Montant requis";
    else if (!Number.isInteger(n) || n <= 0) e.amountXaf = "Le montant doit être un entier supérieur à 0";
    if (!method) e.method = "Moyen de paiement requis";
    if (paidAt && paidAt > today) e.paidAt = "La date de paiement ne peut pas être dans le futur";
    if (note.length > 500) e.note = "500 caractères maximum";
    return e;
  };

  const submit = (ev: React.FormEvent) => {
    ev.preventDefault();
    const e = validate();
    setErrors(e);
    if (Object.keys(e).length > 0 || !method) return;
    setConfirming(true);
  };

  const confirm = () => {
    if (!method) return;
    onSubmit({
      expiresAt,
      amountXaf: Number(amount),
      method,
      // today (the default) is left out: the server records the exact payment time
      ...(paidAt && paidAt !== today ? { paidAt } : {}),
      ...(note.trim() ? { note: note.trim() } : {}),
    });
  };

  const fieldError = (key: Field) =>
    errors[key] ?? (editedFields.has(key) ? undefined : serverErrors[key]);

  const generalError = serverError ? (
    <p role="alert" className="text-sm rounded-lg px-3 py-2 bg-red-50 text-red-700 border border-red-200">
      {serverError}
    </p>
  ) : null;

  if (confirming && method) {
    const summary: [string, string][] = [
      ["Montant", formatXAFFull(Number(amount))],
      ["Moyen de paiement", paymentMethodLabel(method)],
      ["Date du paiement", formatIsoDate(paidAt || today)],
      ["Période couverte", `${formatIsoDate(coveredFrom(today, currentSubscription, payments))} → ${formatIsoDate(expiresAt)}`],
    ];
    return (
      <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="activate-premium-title"
          className="bg-white rounded-xl shadow-2xl p-6 max-w-md w-full mx-4 space-y-4"
        >
          <h3 id="activate-premium-title" className="font-semibold text-gray-900">
            Confirmer l&apos;activation Premium
          </h3>
          {generalError}
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            {summary.map(([label, value]) => (
              <div key={label} className="contents">
                <dt className="text-gray-500 font-medium">{label}</dt>
                <dd className="text-gray-900">{value}</dd>
              </div>
            ))}
          </dl>
          <div className="flex gap-3 justify-end">
            <button
              type="button"
              onClick={() => setConfirming(false)}
              className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
            >
              Modifier
            </button>
            <button
              type="button"
              onClick={confirm}
              disabled={isPending}
              className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-purple-600 hover:bg-purple-700 disabled:opacity-50"
            >
              Confirmer l&apos;activation
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="activate-premium-title"
        onSubmit={submit}
        noValidate
        className="bg-white rounded-xl shadow-2xl p-6 max-w-md w-full mx-4 space-y-4"
      >
        <h3 id="activate-premium-title" className="font-semibold text-gray-900">
          Passer en Premium
        </h3>
        {generalError}

        <div>
          <label htmlFor="premium-expires-at" className={labelClass}>
            Premium jusqu&apos;au
          </label>
          <input
            id="premium-expires-at"
            type="date"
            min={minDate}
            max={maxDate}
            value={expiresAt}
            onChange={(e) => {
              setExpiresAt(e.target.value);
              edited("expiresAt");
            }}
            className={inputClass}
          />
          {fieldError("expiresAt") && (
            <p className="text-xs text-red-600 mt-1">{fieldError("expiresAt")}</p>
          )}
        </div>

        <div>
          <label htmlFor="premium-amount" className={labelClass}>
            Montant (XAF)
          </label>
          <input
            id="premium-amount"
            type="number"
            min={1}
            step={1}
            inputMode="numeric"
            value={amount}
            onChange={(e) => {
              setAmount(e.target.value);
              edited("amountXaf");
            }}
            className={inputClass}
          />
          {fieldError("amountXaf") && (
            <p className="text-xs text-red-600 mt-1">{fieldError("amountXaf")}</p>
          )}
        </div>

        <div>
          <label htmlFor="premium-method" className={labelClass}>
            Moyen de paiement
          </label>
          <select
            id="premium-method"
            value={method}
            onChange={(e) => {
              setMethod(e.target.value as PaymentMethod | "");
              edited("method");
            }}
            className={inputClass}
          >
            <option value="">Choisir…</option>
            {PAYMENT_METHODS.map((m) => (
              <option key={m} value={m}>
                {paymentMethodLabel(m)}
              </option>
            ))}
          </select>
          {fieldError("method") && (
            <p className="text-xs text-red-600 mt-1">{fieldError("method")}</p>
          )}
        </div>

        <div>
          <label htmlFor="premium-paid-at" className={labelClass}>
            Date du paiement
          </label>
          <input
            id="premium-paid-at"
            type="date"
            max={today}
            value={paidAt}
            onChange={(e) => {
              setPaidAt(e.target.value);
              edited("paidAt");
            }}
            className={inputClass}
          />
          {fieldError("paidAt") && <p className="text-xs text-red-600 mt-1">{fieldError("paidAt")}</p>}
        </div>

        <div>
          <label htmlFor="premium-note" className={labelClass}>
            Note (facultatif)
          </label>
          <textarea
            id="premium-note"
            rows={2}
            maxLength={500}
            value={note}
            onChange={(e) => {
              setNote(e.target.value);
              edited("note");
            }}
            className={inputClass}
          />
          {fieldError("note") && <p className="text-xs text-red-600 mt-1">{fieldError("note")}</p>}
        </div>

        <div className="flex gap-3 justify-end">
          <button
            type="button"
            onClick={onCancel}
            className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
          >
            Annuler
          </button>
          <button
            type="submit"
            disabled={isPending}
            className="px-4 py-2 text-sm text-white rounded-lg font-medium bg-purple-600 hover:bg-purple-700 disabled:opacity-50"
          >
            Activer Premium
          </button>
        </div>
      </form>
    </div>
  );
}
