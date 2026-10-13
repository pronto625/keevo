// Story 9.7 — client-side check of a CUSTOM period, same rules as the backend
// (inclusive ISO dates, from ≤ to, at most 366 days; otherwise 422 VALIDATION_ERROR).

export const MAX_CUSTOM_DAYS = 366;

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

function toUtcDay(iso: string): number | null {
  if (!ISO_DATE.test(iso)) return null;
  const t = Date.parse(`${iso}T00:00:00Z`);
  if (Number.isNaN(t)) return null;
  // Reject calendar rollovers (`2026-02-31` would otherwise become 2026-03-03).
  if (new Date(t).toISOString().slice(0, 10) !== iso) return null;
  return t / 86_400_000;
}

/** French error for an invalid custom range, or null when it can be queried. */
export function customRangeError(from: string, to: string): string | null {
  if (!from || !to) return "Choisissez une date de début et une date de fin.";
  const f = toUtcDay(from);
  const t = toUtcDay(to);
  if (f === null || t === null) return "Dates invalides.";
  if (f > t) return "La date de début doit précéder la date de fin.";
  if (t - f + 1 > MAX_CUSTOM_DAYS) return `La période ne peut pas dépasser ${MAX_CUSTOM_DAYS} jours.`;
  return null;
}
