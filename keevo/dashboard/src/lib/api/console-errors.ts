// Story 16.4 — French messages of the « Modules » console errors: the backend's own `error`, never a code.

type ErrorBody = { error?: unknown; domainCode?: unknown; details?: unknown };

function body(err: unknown): ErrorBody | undefined {
  return (err as { response?: { data?: ErrorBody } })?.response?.data;
}

/** The domain code of a failed call (for branching only — never rendered). */
export function errorCode(err: unknown): string | null {
  const code = body(err)?.domainCode;
  return typeof code === "string" ? code : null;
}

/**
 * The French message of a failed console call: for a validation refusal, the first French field message
 * (the generic « Données invalides » says nothing); otherwise the backend's `error`.
 */
export function consoleErrorMessage(err: unknown, fallback = "Une erreur est survenue. Réessayez."): string {
  const data = body(err);
  if (data?.domainCode === "VALIDATION_ERROR" && data.details && typeof data.details === "object") {
    const first = Object.values(data.details as Record<string, unknown>).find(
      (v): v is string => typeof v === "string" && v.trim().length > 0,
    );
    if (first) return first;
    return "Données invalides, vérifiez le formulaire";
  }
  return typeof data?.error === "string" && data.error.trim() ? data.error : fallback;
}

/** The dependents (display names) of a refused deactivation, or null when it is another error. */
export function dependentsOf(err: unknown): string[] | null {
  const data = body(err);
  if (data?.domainCode !== "MODULE_HAS_DEPENDENTS") return null;
  const modules = (data.details as { modules?: unknown } | undefined)?.modules;
  const names = Array.isArray(modules) ? modules.filter((m): m is string => typeof m === "string" && !!m.trim()) : [];
  return names.length > 0 ? names : null;
}

/** Whether a deactivation failed in the module's own shutdown (the « Forcer » case). */
export function isDeactivationFailure(err: unknown): boolean {
  return errorCode(err) === "MODULE_DEACTIVATION_FAILED";
}
