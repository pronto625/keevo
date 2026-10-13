// Story 9-2 — date helpers of the plan section. Business dates are WAT (Africa/Douala).

export const WAT_TIME_ZONE = "Africa/Douala";

/** Today in WAT as yyyy-MM-dd. */
export function todayWat(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: WAT_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
}

/** ISO instant → dd/MM/yyyy in WAT, « — » when missing. */
export function formatInstantWat(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleDateString("fr-FR", { timeZone: WAT_TIME_ZONE });
}

/** yyyy-MM-dd → dd/MM/yyyy (no time-zone shift), « — » when missing. */
export function formatIsoDate(date: string | null | undefined): string {
  if (!date) return "—";
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : "—";
}

/** ISO instant → its WAT calendar date as yyyy-MM-dd, null when missing. */
export function isoDateWat(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : todayWat(d);
}

/** yyyy-MM-dd + n days (calendar arithmetic, no time-zone shift). */
export function addDays(isoDate: string, days: number): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d + days));
  return date.toISOString().slice(0, 10);
}
