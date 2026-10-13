"use client";

import { useEffect, useRef, useState } from "react";
import { useRecipientEstimate, useTenantSearch } from "@/hooks/useGlobalNotifications";
import { deviceCountLabel, GLOBAL_TARGETS, globalTargetOptionLabel } from "@/lib/labels";
import type {
  GlobalNotificationInput,
  GlobalNotificationTarget,
  PickedTenant,
} from "@/types/global-notification";
import { PushPreview } from "./PushPreview";

export const TITLE_MAX = 80;
export const BODY_MAX = 200;
const MAX_DAYS_AHEAD = 30;

type Mode = "now" | "later";
const NO_SERVER_ERRORS: Record<string, string> = {};

/** `Date` → `YYYY-MM-DDTHH:mm` in local time (value format of `datetime-local`). */
function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
type Errors = Partial<Record<"title" | "body" | "targetTenantId" | "scheduledAt", string>>;

/** Client-side checks, same rules as the server (422 VALIDATION_ERROR). */
export function validateCompose(
  values: { title: string; body: string; target: GlobalNotificationTarget; tenant: PickedTenant | null; mode: Mode; scheduledLocal: string },
  now: number = Date.now(),
): Errors {
  const errors: Errors = {};
  const title = values.title.trim();
  const body = values.body.trim();
  if (!title) errors.title = "Le titre est obligatoire";
  else if (title.length > TITLE_MAX) errors.title = `${TITLE_MAX} caractères maximum`;
  if (!body) errors.body = "Le message est obligatoire";
  else if (body.length > BODY_MAX) errors.body = `${BODY_MAX} caractères maximum`;
  if (values.target === "TENANT" && !values.tenant) errors.targetTenantId = "Choisissez l'entreprise";
  if (values.mode === "later") {
    const at = values.scheduledLocal ? new Date(values.scheduledLocal).getTime() : NaN;
    if (Number.isNaN(at)) errors.scheduledAt = "Choisissez la date d'envoi";
    else if (at < now - 60_000) errors.scheduledAt = "La date d'envoi est déjà passée";
    else if (at > now + MAX_DAYS_AHEAD * 24 * 3600_000) errors.scheduledAt = "30 jours maximum à l'avance";
  }
  return errors;
}

function Counter({ length, max }: { length: number; max: number }) {
  return (
    <span className={`text-xs tabular-nums ${length > max ? "text-red-600 font-semibold" : "text-gray-500"}`}>
      {length} / {max}
    </span>
  );
}

/**
 * Compose a global push: title (≤ 80), message (≤ 200), target, now or later, device estimate,
 * previews, then a confirmation « Envoyer à N appareils ? » (« Envoyer » disabled until a fresh estimate is
 * loaded; Escape closes; focus on the confirm button). `onSubmit` rejecting keeps the form.
 */
export function ComposeForm({
  onSubmit,
  isSubmitting,
  serverError,
  serverFieldErrors = NO_SERVER_ERRORS,
}: {
  onSubmit: (input: GlobalNotificationInput) => Promise<unknown>;
  isSubmitting: boolean;
  serverError?: string | null;
  serverFieldErrors?: Record<string, string>;
}) {
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [target, setTarget] = useState<GlobalNotificationTarget>("ALL");
  const [tenant, setTenant] = useState<PickedTenant | null>(null);
  const [tenantSearch, setTenantSearch] = useState("");
  const [mode, setMode] = useState<Mode>("now");
  const [scheduledLocal, setScheduledLocal] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [confirming, setConfirming] = useState(false);
  // Server field errors are hidden once their field is edited (reset when a new answer arrives).
  const [seenServerErrors, setSeenServerErrors] = useState(serverFieldErrors);
  const [editedFields, setEditedFields] = useState<ReadonlySet<string>>(new Set());
  if (seenServerErrors !== serverFieldErrors) {
    setSeenServerErrors(serverFieldErrors);
    setEditedFields(new Set());
  }
  const edited = (...fields: string[]) =>
    setEditedFields((prev) => new Set([...prev, ...fields]));
  const confirmRef = useRef<HTMLButtonElement>(null);
  const [scheduleWindow] = useState(() => {
    const now = new Date();
    return {
      min: toLocalInput(now),
      max: toLocalInput(new Date(now.getTime() + MAX_DAYS_AHEAD * 24 * 3600_000)),
    };
  });

  const estimate = useRecipientEstimate(target, tenant?.id ?? null);
  const estimateReady = estimate.data != null && !estimate.isFetching;
  const tenants = useTenantSearch(target === "TENANT" && !tenant ? tenantSearch : "");
  const visibleServerErrors = Object.fromEntries(
    Object.entries(serverFieldErrors).filter(([k]) => !editedFields.has(k)),
  );
  const shownErrors: Errors = { ...visibleServerErrors, ...errors };

  useEffect(() => {
    if (!confirming) return;
    confirmRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setConfirming(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [confirming]);

  const reset = () => {
    setTitle("");
    setBody("");
    setTarget("ALL");
    setTenant(null);
    setTenantSearch("");
    setMode("now");
    setScheduledLocal("");
    setErrors({});
  };

  const askConfirm = () => {
    const e = validateCompose({ title, body, target, tenant, mode, scheduledLocal });
    setErrors(e);
    if (Object.keys(e).length === 0) setConfirming(true);
  };

  const confirm = async () => {
    try {
      await onSubmit({
        title: title.trim(),
        body: body.trim(),
        target,
        targetTenantId: target === "TENANT" ? tenant?.id ?? null : null,
        scheduledAt: mode === "later" ? new Date(scheduledLocal).toISOString() : null,
      });
      setConfirming(false);
      reset();
    } catch {
      setConfirming(false);
    }
  };

  const estimateText =
    target === "TENANT" && !tenant
      ? "Choisissez l'entreprise pour estimer les appareils ciblés"
      : estimate.isLoading
        ? "Estimation des appareils ciblés…"
        : estimate.data != null
          ? `${deviceCountLabel(estimate.data)} ${estimate.data > 1 ? "ciblés" : "ciblé"}`
          : "Estimation indisponible";

  return (
    <div className="bg-white rounded-lg shadow p-4 grid gap-6 lg:grid-cols-[1fr_320px]">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          askConfirm();
        }}
        noValidate
      >
        <div>
          <div className="flex items-center justify-between">
            <label htmlFor="push-title" className="text-sm font-medium text-gray-700">Titre</label>
            <Counter length={title.length} max={TITLE_MAX} />
          </div>
          <input
            id="push-title"
            value={title}
            onChange={(e) => {
              setTitle(e.target.value);
              edited("title");
            }}
            className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          {shownErrors.title && <p className="text-xs text-red-600 mt-1">{shownErrors.title}</p>}
        </div>

        <div>
          <div className="flex items-center justify-between">
            <label htmlFor="push-body" className="text-sm font-medium text-gray-700">Message</label>
            <Counter length={body.length} max={BODY_MAX} />
          </div>
          <textarea
            id="push-body"
            rows={3}
            value={body}
            onChange={(e) => {
              setBody(e.target.value);
              edited("body");
            }}
            className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          {shownErrors.body && <p className="text-xs text-red-600 mt-1">{shownErrors.body}</p>}
        </div>

        <div>
          <label htmlFor="push-target" className="text-sm font-medium text-gray-700">Cible</label>
          <select
            id="push-target"
            value={target}
            onChange={(e) => {
              setTarget(e.target.value as GlobalNotificationTarget);
              setTenant(null);
              setTenantSearch("");
              edited("target", "targetTenantId");
            }}
            className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 bg-white"
          >
            {GLOBAL_TARGETS.map((t) => (
              <option key={t} value={t}>
                {globalTargetOptionLabel(t)}
              </option>
            ))}
          </select>
        </div>

        {target === "TENANT" && (
          <div>
            {tenant ? (
              <div className="flex items-center gap-3 text-sm">
                <span className="font-medium text-gray-900">{tenant.name}</span>
                <button type="button" onClick={() => setTenant(null)} className="text-xs text-blue-700 underline">
                  Changer
                </button>
              </div>
            ) : (
              <>
                <label htmlFor="push-tenant-search" className="text-sm font-medium text-gray-700">
                  Entreprise
                </label>
                <input
                  id="push-tenant-search"
                  placeholder="Rechercher par nom"
                  value={tenantSearch}
                  onChange={(e) => setTenantSearch(e.target.value)}
                  className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 bg-white"
                />
                {tenants.data && tenants.data.length > 0 && (
                  <ul className="mt-1 border border-gray-200 rounded-lg divide-y divide-gray-100 max-h-48 overflow-auto">
                    {tenants.data.map((t) => (
                      <li key={t.id}>
                        <button
                          type="button"
                          onClick={() => {
                            setTenant(t);
                            edited("targetTenantId");
                          }}
                          className="w-full text-left px-3 py-2 text-sm hover:bg-gray-50"
                        >
                          {t.name}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
                {tenants.data && tenants.data.length === 0 && (
                  <p className="text-xs text-gray-500 mt-1">Aucune entreprise trouvée</p>
                )}
              </>
            )}
            {shownErrors.targetTenantId && (
              <p className="text-xs text-red-600 mt-1">{shownErrors.targetTenantId}</p>
            )}
          </div>
        )}

        <fieldset>
          <legend className="text-sm font-medium text-gray-700">Envoi</legend>
          <div className="mt-1 flex items-center gap-4 text-sm text-gray-700">
            <label className="flex items-center gap-1.5">
              <input
                type="radio"
                name="push-mode"
                checked={mode === "now"}
                onChange={() => {
                  setMode("now");
                  edited("scheduledAt");
                }}
              />
              Maintenant
            </label>
            <label className="flex items-center gap-1.5">
              <input type="radio" name="push-mode" checked={mode === "later"} onChange={() => setMode("later")} />
              Programmer
            </label>
          </div>
          {mode === "later" && (
            <div className="mt-2">
              <label htmlFor="push-scheduled-at" className="sr-only">Date et heure d&apos;envoi</label>
              <input
                id="push-scheduled-at"
                type="datetime-local"
                value={scheduledLocal}
                min={scheduleWindow.min}
                max={scheduleWindow.max}
                onChange={(e) => {
                  setScheduledLocal(e.target.value);
                  edited("scheduledAt");
                }}
                className="border border-gray-300 rounded-lg px-3 py-2 text-sm text-gray-900 bg-white"
              />
            </div>
          )}
          {shownErrors.scheduledAt && <p className="text-xs text-red-600 mt-1">{shownErrors.scheduledAt}</p>}
        </fieldset>

        <p className="text-sm text-gray-600" data-testid="recipient-estimate">{estimateText}</p>

        {serverError && (
          <p role="alert" className="text-sm text-red-600">{serverError}</p>
        )}

        <button
          type="submit"
          disabled={isSubmitting || !estimateReady}
          className="px-4 py-2 text-sm text-white bg-blue-600 hover:bg-blue-700 rounded-lg font-medium disabled:opacity-50"
        >
          {mode === "later" ? "Programmer l'envoi" : "Envoyer"}
        </button>
      </form>

      <PushPreview title={title} body={body} />

      {confirming && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50">
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="push-confirm-title"
            className="bg-white rounded-xl shadow-2xl p-6 max-w-sm w-full mx-4"
          >
            <h3 id="push-confirm-title" className="font-semibold text-gray-900 mb-2">
              {`Envoyer à ${deviceCountLabel(estimate.data ?? 0)} ?`}
            </h3>
            <p className="text-sm text-gray-500 mb-4">
              {mode === "later"
                ? `L'annonce « ${title.trim()} » sera envoyée le ${new Date(scheduledLocal).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" })}.`
                : `L'annonce « ${title.trim()} » sera envoyée immédiatement.`}
            </p>
            <div className="flex gap-3 justify-end">
              <button
                type="button"
                onClick={() => setConfirming(false)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg text-gray-700 hover:bg-gray-50"
              >
                Annuler
              </button>
              <button
                ref={confirmRef}
                type="button"
                onClick={() => void confirm()}
                disabled={isSubmitting}
                className="px-4 py-2 text-sm text-white bg-blue-600 hover:bg-blue-700 rounded-lg font-medium disabled:opacity-50"
              >
                Confirmer l&apos;envoi
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
