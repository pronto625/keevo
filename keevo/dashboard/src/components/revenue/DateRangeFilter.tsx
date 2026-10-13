"use client";

interface DateRangeFilterProps {
  /** Inclusive ISO date (yyyy-MM-dd). */
  from: string;
  to: string;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  /** Back to the page's default range. */
  onReset: () => void;
  /** French error of the current range (from the page's range validator), null when valid. */
  error: string | null;
  /** Label of the reset button (default « Ce mois-ci »). */
  resetLabel?: string;
}

/**
 * Inclusive « Du / Au » date range with a reset button and the French error of the range. Shared by the
 * « Revenus » KPIs (9-3) and the « Top boutiques » ranking of « Statistiques » (9-4); each page owns
 * its defaults and validation rules.
 */
export function DateRangeFilter({
  from,
  to,
  onFromChange,
  onToChange,
  onReset,
  error,
  resetLabel = "Ce mois-ci",
}: DateRangeFilterProps) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label="Période">
        <label className="text-sm text-gray-600 flex flex-col gap-1">
          Du
          <input
            type="date"
            value={from}
            max={to || undefined}
            onChange={(e) => onFromChange(e.target.value)}
            className="border border-gray-300 rounded-lg px-2 py-1 text-gray-800"
          />
        </label>
        <label className="text-sm text-gray-600 flex flex-col gap-1">
          Au
          <input
            type="date"
            value={to}
            min={from || undefined}
            onChange={(e) => onToChange(e.target.value)}
            className="border border-gray-300 rounded-lg px-2 py-1 text-gray-800"
          />
        </label>
        <button
          type="button"
          onClick={onReset}
          className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg text-gray-700 bg-white hover:bg-gray-50"
        >
          {resetLabel}
        </button>
        {error && (
          <p role="alert" className="text-sm text-red-600 pb-1">
            {error}
          </p>
        )}
      </div>
    </div>
  );
}
