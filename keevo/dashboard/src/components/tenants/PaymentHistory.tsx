"use client";

import { formatXAFFull } from "@/lib/format";
import { paymentMethodLabel } from "@/lib/labels";
import type { TenantPayment } from "@/types/tenant";
import { formatInstantWat, formatIsoDate } from "./planDates";

interface PaymentHistoryProps {
  payments: TenantPayment[] | undefined;
  isLoading: boolean;
  isError: boolean;
}

/** Payment history of a tenant, newest first (Story 9-2). French labels only, no ids. */
export function PaymentHistory({ payments, isLoading, isError }: PaymentHistoryProps) {
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
        </tr>
      </thead>
      <tbody className="divide-y divide-gray-100">
        {payments.map((p, i) => (
          <tr key={`${p.paidAt}-${i}`}>
            <td className="py-2 text-gray-700">{formatInstantWat(p.paidAt)}</td>
            <td className="py-2 text-right text-gray-900 font-medium">{formatXAFFull(p.amountXaf)}</td>
            <td className="py-2 pl-3 text-gray-600">{paymentMethodLabel(p.method)}</td>
            <td className="py-2 text-gray-600">
              {formatIsoDate(p.periodStart)} → {formatIsoDate(p.periodEnd)}
              {p.note && <span className="block text-xs text-gray-400">{p.note}</span>}
            </td>
            <td className="py-2 text-gray-500">{p.recordedByLabel}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
