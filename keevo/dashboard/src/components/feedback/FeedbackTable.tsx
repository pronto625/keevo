"use client";

import { feedbackTypeLabel, platformLabel, priorityLabel, screenLabel } from "@/lib/labels";
import type { AdminFeedback, FeedbackPage } from "@/types/feedback";

interface FeedbackTableProps {
  data: FeedbackPage | undefined;
  isLoading: boolean;
  page: number;
  onPageChange: (page: number) => void;
  onHandle: (id: string) => void;
  onUnhandle: (id: string) => void;
  /** Feedback whose action is in flight (their buttons are disabled). */
  pendingIds: ReadonlySet<string>;
}

const HEADERS = [
  "Date",
  "Entreprise",
  "Utilisateur",
  "Type",
  "Priorité",
  "Écran",
  "Version de l'app",
  "Description",
  "",
];

function formatDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return `${d.toLocaleDateString("fr-FR")} ${d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })}`;
}

/** « Traité le dd/MM/yyyy » (day in Africa/Douala); « Traité » without a date. */
export function handledLabel(handledAt: string | null): string {
  const d = handledAt ? new Date(handledAt) : null;
  if (!d || Number.isNaN(d.getTime())) return "Traité";
  return `Traité le ${d.toLocaleDateString("fr-FR", { timeZone: "Africa/Douala" })}`;
}

function appVersionLabel(f: AdminFeedback): string {
  const version = f.appVersion?.trim();
  const platform = f.platform ? platformLabel(f.platform) : null;
  if (version && platform && platform !== "—") return `${version} · ${platform}`;
  return version || (platform && platform !== "—" ? platform : "—");
}

export function FeedbackTable({
  data,
  isLoading,
  page,
  onPageChange,
  onHandle,
  onUnhandle,
  pendingIds,
}: FeedbackTableProps) {
  const total = data?.totalElements ?? 0;
  const size = data?.size ?? 20;
  const start = page * size + 1;
  const end = Math.min(start + size - 1, total);

  return (
    <div>
      <p className="text-sm text-gray-500 mb-2">
        {total > 0
          ? `Affichage ${start}–${end} sur ${total} ${total > 1 ? "retours" : "retour"}`
          : "Aucun retour"}
      </p>

      <div className="overflow-x-auto rounded border border-gray-200">
        <table className="min-w-full text-sm divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              {HEADERS.map((h, i) => (
                <th
                  key={`${h}-${i}`}
                  className="px-3 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {isLoading ? (
              <tr>
                <td colSpan={HEADERS.length} className="px-4 py-8 text-center text-gray-400">
                  Chargement…
                </td>
              </tr>
            ) : !data?.items.length ? (
              <tr>
                <td colSpan={HEADERS.length} className="px-4 py-8 text-center text-gray-400">
                  Aucun retour pour ces filtres
                </td>
              </tr>
            ) : (
              data.items.map((f) => {
                const high = f.priority?.toUpperCase() === "HIGH";
                return (
                  <tr
                    key={f.id}
                    data-priority-high={high ? "true" : undefined}
                    className={high ? "bg-red-50 border-l-4 border-red-400" : ""}
                  >
                    <td className="px-3 py-3 whitespace-nowrap text-gray-600">{formatDateTime(f.submittedAt)}</td>
                    <td className="px-3 py-3 font-medium text-gray-900">{f.tenantName}</td>
                    <td className="px-3 py-3 text-gray-700">{f.userLabel}</td>
                    <td className="px-3 py-3 text-gray-700">{feedbackTypeLabel(f.type)}</td>
                    <td className="px-3 py-3">
                      <span
                        className={`px-2 py-0.5 rounded text-xs font-medium ${
                          high ? "bg-red-100 text-red-800" : "bg-gray-100 text-gray-700"
                        }`}
                      >
                        {priorityLabel(f.priority)}
                      </span>
                    </td>
                    <td className="px-3 py-3 text-gray-600">{screenLabel(f.screenContext)}</td>
                    <td className="px-3 py-3 whitespace-nowrap text-gray-600">{appVersionLabel(f)}</td>
                    <td className="px-3 py-3 text-gray-800 max-w-md whitespace-pre-line break-words">{f.description}</td>
                    <td className="px-3 py-3 whitespace-nowrap">
                      {f.handled ? (
                        <div className="flex flex-col items-start gap-1">
                          <span className="text-xs text-green-700 font-medium">{handledLabel(f.handledAt)}</span>
                          <button
                            type="button"
                            onClick={() => onUnhandle(f.id)}
                            disabled={pendingIds.has(f.id)}
                            className="text-xs text-gray-500 underline hover:text-gray-800 disabled:opacity-50"
                          >
                            Remettre à traiter
                          </button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          onClick={() => onHandle(f.id)}
                          disabled={pendingIds.has(f.id)}
                          className="px-2 py-1 text-xs border border-gray-300 rounded text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-50"
                        >
                          Marquer comme traité
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {total > size && (
        <div className="flex justify-end gap-2 mt-3">
          <button
            type="button"
            onClick={() => onPageChange(page - 1)}
            disabled={page === 0}
            className="px-3 py-1 text-sm border rounded disabled:opacity-40"
          >
            ← Précédent
          </button>
          <button
            type="button"
            onClick={() => onPageChange(page + 1)}
            disabled={end >= total}
            className="px-3 py-1 text-sm border rounded disabled:opacity-40"
          >
            Suivant →
          </button>
        </div>
      )}
    </div>
  );
}
