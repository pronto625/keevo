"use client";

import type { Period } from "@/types/operations";
import { periodLabel } from "@/lib/labels";
import { customRangeError } from "@/lib/period";

interface PeriodSelectorProps {
  period: Period;
  onPeriodChange: (period: Period) => void;
  /** Inclusive ISO date (yyyy-MM-dd), used when period is CUSTOM. */
  customFrom: string;
  customTo: string;
  onCustomFromChange: (value: string) => void;
  onCustomToChange: (value: string) => void;
}

const PERIODS: Period[] = ["TODAY", "7D", "30D", "90D", "CUSTOM"];

export function PeriodSelector({
  period,
  onPeriodChange,
  customFrom,
  customTo,
  onCustomFromChange,
  onCustomToChange,
}: PeriodSelectorProps) {
  const error = period === "CUSTOM" ? customRangeError(customFrom, customTo) : null;

  return (
    <div className="bg-white rounded-lg shadow p-4 space-y-3">
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Période">
        <span className="text-sm text-gray-500 mr-1">Période :</span>
        {PERIODS.map((p) => {
          const selected = p === period;
          return (
            <button
              key={p}
              type="button"
              aria-pressed={selected}
              onClick={() => onPeriodChange(p)}
              className={`px-3 py-1.5 text-sm rounded-lg border ${
                selected
                  ? "bg-gray-900 text-white border-gray-900"
                  : "bg-white text-gray-700 border-gray-300 hover:bg-gray-50"
              }`}
            >
              {periodLabel(p)}
            </button>
          );
        })}
      </div>

      {period === "CUSTOM" && (
        <div className="flex flex-wrap items-end gap-4">
          <label className="text-sm text-gray-600 flex flex-col gap-1">
            Du
            <input
              type="date"
              value={customFrom}
              max={customTo || undefined}
              onChange={(e) => onCustomFromChange(e.target.value)}
              className="border border-gray-300 rounded-lg px-2 py-1 text-gray-800"
            />
          </label>
          <label className="text-sm text-gray-600 flex flex-col gap-1">
            Au
            <input
              type="date"
              value={customTo}
              min={customFrom || undefined}
              onChange={(e) => onCustomToChange(e.target.value)}
              className="border border-gray-300 rounded-lg px-2 py-1 text-gray-800"
            />
          </label>
          {error && (
            <p role="alert" className="text-sm text-red-600 pb-1">
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
