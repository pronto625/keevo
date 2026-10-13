"use client";

function RetryButton({ onRetry }: { onRetry?: () => void }) {
  if (!onRetry) return null;
  return (
    <button
      type="button"
      onClick={onRetry}
      className="ml-3 px-2 py-0.5 text-xs font-medium border border-red-300 rounded text-red-700 bg-white hover:bg-red-50"
    >
      Réessayer
    </button>
  );
}

/** First load failed: nothing to show. */
export function LoadError({ onRetry }: { onRetry?: () => void }) {
  return (
    <div
      role="alert"
      className="flex items-center justify-center text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-4 py-3"
    >
      <span>Erreur de chargement</span>
      <RetryButton onRetry={onRetry} />
    </div>
  );
}

/** A refresh failed while cached data stays on screen. */
export function StaleDataWarning({ onRetry }: { onRetry?: () => void }) {
  return (
    <div
      role="alert"
      className="flex items-center text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-4 py-2"
    >
      <span>Échec de l&apos;actualisation — données possiblement obsolètes</span>
      <RetryButton onRetry={onRetry} />
    </div>
  );
}
