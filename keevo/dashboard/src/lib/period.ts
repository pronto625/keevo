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

// Story 9-3 — date range of the « Revenus » page (inclusive ISO dates, years 2000..9999, from ≤ to,
// at most 24 months, default current month; otherwise 422 VALIDATION_ERROR). Same rules as the backend.

export const MAX_REVENUE_RANGE_MONTHS = 24;
export const MIN_REVENUE_YEAR = 2000;
export const MAX_REVENUE_YEAR = 9999;
/** The backend computes « today » in WAT. */
export const REVENUE_TIME_ZONE = "Africa/Douala";

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

/** Today's date (yyyy-MM-dd) in Africa/Douala, whatever the browser's time zone. */
export function todayInDouala(now: Date = new Date()): string {
  // en-CA formats as yyyy-MM-dd.
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: REVENUE_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
}

/** Current calendar month as `YYYY-MM` in Africa/Douala. */
export function currentMonth(now: Date = new Date()): string {
  return todayInDouala(now).slice(0, 7);
}

function daysInMonth(year: number, month1: number): number {
  return new Date(Date.UTC(year, month1, 0)).getUTCDate();
}

/** First and last day of a `YYYY-MM` month. */
export function monthRange(month: string): { from: string; to: string } {
  const [y, m] = month.split("-").map(Number);
  return { from: `${month}-01`, to: `${month}-${pad(daysInMonth(y, m))}` };
}

/** True for a valid `YYYY-MM` month. */
export function isValidMonth(month: string): boolean {
  if (!/^\d{4}-\d{2}$/.test(month)) return false;
  const m = Number(month.slice(5));
  return m >= 1 && m <= 12;
}

/** `iso` + n months, day clamped to the target month's last day (like Java's `LocalDate.plusMonths`). */
export function plusMonths(iso: string, months: number): string {
  const [y, m, d] = iso.split("-").map(Number);
  const total = y * 12 + (m - 1) + months;
  const year = Math.floor(total / 12);
  const month1 = (total % 12) + 1;
  const day = Math.min(d, daysInMonth(year, month1));
  return `${String(year).padStart(4, "0")}-${pad(month1)}-${pad(day)}`;
}

/** French error when a date of a valid ISO range is outside 2000..9999 (backend 422), else null. */
export function yearBoundError(from: string, to: string): string | null {
  const years = [Number(from.slice(0, 4)), Number(to.slice(0, 4))];
  if (years.some((y) => y < MIN_REVENUE_YEAR || y > MAX_REVENUE_YEAR)) {
    return `Les dates doivent être comprises entre ${MIN_REVENUE_YEAR} et ${MAX_REVENUE_YEAR}.`;
  }
  return null;
}

/** French error for an invalid revenue range, or null when it can be queried. */
export function revenueRangeError(from: string, to: string): string | null {
  if (!from || !to) return "Choisissez une date de début et une date de fin.";
  if (toUtcDay(from) === null || toUtcDay(to) === null) return "Dates invalides.";
  const yearError = yearBoundError(from, to);
  if (yearError) return yearError;
  // ISO dates compare as strings.
  if (from > to) return "La date de début doit précéder la date de fin.";
  // Same rule as the backend: to < from.plusMonths(24).
  if (to >= plusMonths(from, MAX_REVENUE_RANGE_MONTHS)) {
    return `La période ne peut pas dépasser ${MAX_REVENUE_RANGE_MONTHS} mois.`;
  }
  return null;
}

// Story 9-4 — « Top boutiques » range of the « Analytics » page (default last 30 days, WAT).

/** `iso` + n days (UTC arithmetic on the calendar date: no time-zone shift). */
export function plusDays(iso: string, days: number): string {
  const t = Date.parse(`${iso}T00:00:00Z`) + days * 86_400_000;
  return new Date(t).toISOString().slice(0, 10);
}

/** The last `days` days ending today in Africa/Douala (inclusive). */
export function lastDaysRange(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = todayInDouala(now);
  return { from: plusDays(to, -(days - 1)), to };
}

/**
 * French error of the « Top boutiques » range, or null — same rules as the backend: valid ISO dates,
 * years 2000..9999, from ≤ to, at most 366 days (otherwise 422 VALIDATION_ERROR).
 */
export function topStoresRangeError(from: string, to: string): string | null {
  if (!from || !to) return "Choisissez une date de début et une date de fin.";
  if (toUtcDay(from) === null || toUtcDay(to) === null) return "Dates invalides.";
  return yearBoundError(from, to) ?? customRangeError(from, to);
}
