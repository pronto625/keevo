"use client";

export const PERIOD_REQUIRED_MESSAGE = "Choisissez une période valide pour afficher les données";

/** Shown instead of an empty state while the CUSTOM range is incomplete or invalid (no query runs). */
export function PeriodRequired({ className = "" }: { className?: string }) {
  return (
    <p className={`text-sm text-gray-500 bg-gray-50 border border-dashed border-gray-300 rounded-lg px-4 py-6 text-center ${className}`}>
      {PERIOD_REQUIRED_MESSAGE}
    </p>
  );
}
