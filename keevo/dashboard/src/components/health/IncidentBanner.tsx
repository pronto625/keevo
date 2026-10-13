"use client";

import { useHealthStatus } from "@/hooks/useHealth";

/** « a », « a et b », « a, b et c » */
export function joinMetrics(labels: string[]): string {
  if (labels.length <= 1) return labels[0] ?? "";
  return `${labels.slice(0, -1).join(", ")} et ${labels[labels.length - 1]}`;
}

/**
 * Sticky banner on every admin page (Story 9-5): red while a health threshold is breached (each
 * metric phrased by the server, e.g. « Base de données injoignable »), grey when the status itself
 * cannot be read after retries — exactly what an outage looks like from the dashboard.
 */
export function IncidentBanner() {
  const { data, isError } = useHealthStatus();
  if (isError) {
    return (
      <div role="alert" className="sticky top-0 z-40 bg-gray-700 text-white text-sm font-medium px-6 py-2 shadow">
        État du serveur inconnu — le tableau de bord ne parvient pas à joindre le serveur.
      </div>
    );
  }
  if (!data?.incident) return null;
  const metrics = data.breachedMetrics.length ? joinMetrics(data.breachedMetrics) : "Seuil dépassé";
  return (
    <div role="alert" className="sticky top-0 z-40 bg-red-600 text-white text-sm font-medium px-6 py-2 shadow">
      🔴 Incident en cours — {metrics}. Vérifiez les journaux du serveur.
    </div>
  );
}
