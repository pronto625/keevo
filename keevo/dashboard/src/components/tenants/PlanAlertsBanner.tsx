"use client";

import { planLabel } from "@/lib/labels";
import type { PlanAlert } from "@/types/tenant";
import { formatInstantWat } from "./planDates";

interface PlanAlertsBannerProps {
  alerts: PlanAlert[] | undefined;
  /** The alerts query failed: shown as unavailable, never as « no alerts ». */
  isError?: boolean;
  onRetry?: () => void;
  /** Opens the tenant drawer. */
  onSelect?: (tenantId: string) => void;
}

function planWord(alert: PlanAlert): string {
  return alert.plan ? `plan ${planLabel(alert.plan)}` : "plan";
}

function expiringText(alert: PlanAlert): string {
  const daysLeft = alert.daysLeft;
  if (daysLeft === null || daysLeft <= 0) return `${planWord(alert)} expire aujourd'hui`;
  if (daysLeft === 1) return `${planWord(alert)} expire dans 1 jour`;
  return `${planWord(alert)} expire dans ${daysLeft} jours`;
}

/** Alert text of one entry, French only, the tenant shown by its business name. */
export function planAlertText(alert: PlanAlert): string {
  const name = alert.tenantName?.trim() || "Sans nom";
  if (alert.kind === "EXPIRED") {
    return `⚠️ ${name} — ${planWord(alert)} expiré, repassé en Gratuit le ${formatInstantWat(alert.occurredAt)}`;
  }
  return `⚠️ ${name} — ${expiringText(alert)}`;
}

/** Banner list of plans expiring within 7 days / expired in the last 7 days (Story 9-2, FR81). */
export function PlanAlertsBanner({ alerts, isError = false, onRetry, onSelect }: PlanAlertsBannerProps) {
  if (isError) {
    return (
      <section
        role="alert"
        className="bg-red-50 border border-red-200 rounded-lg p-3 flex items-center justify-between gap-3"
      >
        <span className="text-sm text-red-700">Alertes d&apos;abonnement indisponibles</span>
        <button
          type="button"
          onClick={onRetry}
          className="text-sm px-3 py-1 border border-red-300 rounded-lg text-red-700 hover:bg-red-100"
        >
          Réessayer
        </button>
      </section>
    );
  }
  if (!alerts || alerts.length === 0) return null;
  return (
    <section
      aria-label="Alertes d'abonnement"
      className="bg-amber-50 border border-amber-200 rounded-lg p-3 space-y-1"
    >
      {alerts.map((a, i) => (
        <button
          key={`${a.tenantId}-${a.kind}-${i}`}
          type="button"
          onClick={() => onSelect?.(a.tenantId)}
          className="block w-full text-left text-sm text-amber-800 hover:underline"
        >
          {planAlertText(a)}
        </button>
      ))}
    </section>
  );
}
