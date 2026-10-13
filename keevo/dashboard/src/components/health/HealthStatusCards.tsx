"use client";

import type { SystemHealth } from "@/types/health";
import { relativeTime } from "@/lib/format";

interface HealthStatusCardsProps {
  data: SystemHealth | undefined;
  isLoading: boolean;
}

const NUMBER = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 0 });
const PERCENT = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 2 });

function ms(value: number | null): string {
  return value === null ? "—" : `${NUMBER.format(value)} ms`;
}

function percent(value: number | null): string {
  return value === null ? "—" : `${PERCENT.format(value)} %`;
}

function count(value: number | null): string {
  return value === null ? "—" : NUMBER.format(value);
}

interface CardProps {
  label: string;
  value: React.ReactNode;
  threshold: string;
  breached: boolean;
  detail?: React.ReactNode;
}

/** One metric: red card + warning icon when its threshold is breached. */
function HealthCard({ label, value, threshold, breached, detail }: CardProps) {
  return (
    <div
      data-testid="health-card"
      data-breached={breached ? "true" : "false"}
      className={`rounded-lg shadow p-4 ${breached ? "bg-red-50 border border-red-300" : "bg-white"}`}
    >
      <div className="flex items-center justify-between gap-2">
        <p className={`text-sm ${breached ? "text-red-700 font-medium" : "text-gray-500"}`}>{label}</p>
        {breached && (
          <span role="img" aria-label="Seuil dépassé" className="text-red-600">
            ⚠️
          </span>
        )}
      </div>
      <p className={`text-3xl font-bold mt-1 ${breached ? "text-red-700" : "text-gray-900"}`}>{value}</p>
      {detail && <p className="text-xs text-gray-500 mt-1">{detail}</p>}
      <p className={`text-xs mt-2 ${breached ? "text-red-600" : "text-gray-400"}`}>{threshold}</p>
    </div>
  );
}

function SkeletonCard() {
  return (
    <div className="bg-white rounded-lg shadow p-4 animate-pulse">
      <div className="h-4 bg-gray-200 rounded w-24 mb-3" />
      <div className="h-8 bg-gray-200 rounded w-16" />
    </div>
  );
}

/** Status card grid of « Santé système » (Story 9-5, FR83). */
export function HealthStatusCards({ data, isLoading }: HealthStatusCardsProps) {
  if (isLoading || !data) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {Array.from({ length: 6 }, (_, i) => (
          <SkeletonCard key={i} />
        ))}
      </div>
    );
  }

  return (
    <section className="space-y-3">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <HealthCard
          label="Disponibilité (30 jours)"
          value={percent(data.uptimePercent30d)}
          threshold="Seuil : au moins 99 %"
          breached={data.uptimeBreached}
          detail={data.uptimePercent30d === null ? "Pas encore de mesure" : undefined}
        />
        <HealthCard
          label="Temps de réponse (p99)"
          value={ms(data.p99Ms)}
          threshold="Seuil : 2 000 ms au plus"
          breached={data.latencyBreached}
          detail={`Médiane ${ms(data.p50Ms)} · p95 ${ms(data.p95Ms)}`}
        />
        <HealthCard
          label="Taux d'erreurs (1 h)"
          value={percent(data.errorRatePercent1h)}
          threshold="Seuil : 2 % au plus"
          breached={data.errorRateBreached}
        />
        <HealthCard
          label="Base de données"
          value={data.databaseOk ? "✅ Opérationnelle" : "❌ Injoignable"}
          threshold="Contrôle de connexion en direct"
          breached={data.databaseBreached}
        />
        <HealthCard
          label="Connexions actives à la base"
          value={
            data.dbMaxConnections === null
              ? count(data.dbActiveConnections)
              : `${count(data.dbActiveConnections)} / ${count(data.dbMaxConnections)}`
          }
          threshold="Seuil : moins de 80 % du maximum"
          breached={data.dbActiveConnectionsBreached}
        />
        <HealthCard
          label="Refus de synchronisation (1 h)"
          value={count(data.syncRefusals1h)}
          threshold="Seuil : 1 000 par heure au plus"
          breached={data.syncRefusalsBreached}
        />
      </div>
      <p className="text-xs text-gray-500">
        {data.lastSampleAt
          ? `Dernière mesure ${relativeTime(data.lastSampleAt)} · mesures toutes les 5 minutes`
          : "Aucune mesure enregistrée pour l'instant · mesures toutes les 5 minutes"}
      </p>
    </section>
  );
}
