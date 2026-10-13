"use client";

import { formatXAFFull } from "@/lib/format";
import { paymentMethodLabel } from "@/lib/labels";
import type { TenantPayment } from "@/types/tenant";
import { formatInstantWat, formatIsoDate } from "./planDates";

interface PaymentHistoryProps {
  payments: TenantPayment[] | undefined;
  isLoading: boolean;
  isError: boolean;
  /** « Annuler ce paiement » on each counted payment (omitted → read-only history). */
  onVoid?: (payment: TenantPayment) => void;
  voidDisabled?: boolean;
}

/**
 * Payment history of a tenant, newest first (Story 9-2). French labels only, no ids. A voided
 * payment stays listed, struck through, with « Annulé » and its reason.
 */
export function PaymentHistory({ payments, isLoading, isError, onVoid, voidDisabled = false }: PaymentHistoryProps) {
  if (isLoading) {
    return <div className="h-16 bg-gray-100 rounded animate-pulse" aria-label="Chargement des paiements" />;
  }
  if (isError) {
    return <p className="text-sm text-red-600">Erreur de chargement des paiements</p>;
  }
  if (!payments || payments.length === 0) {
    return <p className="text-sm text-gray-400">Aucun paiement enregistré</p>;
  }
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="border-b border-gray-200">
          <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Date</th>
          <th className="text-right py-2 text-xs font-medium text-gray-500 uppercase">Montant</th>
          <th className="text-left py-2 pl-3 text-xs font-medium text-gray-500 uppercase">Moyen</th>
          <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Période</th>
          <th className="text-left py-2 text-xs font-medium text-gray-500 uppercase">Par</th>
          {onVoid && <th className="py-2"><span className="sr-only">Actions</span></th>}
        </tr>
      </thead>
      <tbody className="divide-y divide-gray-100">
        {payments.map((p, i) => {
          const voided = Boolean(p.voidedAt);
          const struck = voided ? "line-through text-gray-400" : "";
          return (
            <tr key={p.id ?? `${p.paidAt}-${i}`}>
              <td className={`py-2 ${struck || "text-gray-700"}`}>{formatInstantWat(p.paidAt)}</td>
              <td className={`py-2 text-right font-medium ${struck || "text-gray-900"}`}>
                {formatXAFFull(p.amountXaf)}
              </td>
              <td className={`py-2 pl-3 ${struck || "text-gray-600"}`}>{paymentMethodLabel(p.method)}</td>
              <td className="py-2 text-gray-600">
                <span className={struck}>
                  {formatIsoDate(p.periodStart)} → {formatIsoDate(p.periodEnd)}
                </span>
                {p.note && <span className="block text-xs text-gray-400">{p.note}</span>}
                {voided && (
                  <span className="block text-xs text-red-600">
                    Annulé{p.voidReason ? ` — motif : ${p.voidReason}` : ""}
                  </span>
                )}
              </td>
              <td className="py-2 text-gray-500">{p.recordedByLabel}</td>
              {onVoid && (
                <td className="py-2 text-right">
                  {!voided && (
                    <button
                      type="button"
                      disabled={voidDisabled}
                      onClick={() => onVoid(p)}
                      className="text-xs text-red-600 hover:underline disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      Annuler ce paiement
                    </button>
                  )}
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
