"use client";

import { isSendPending, isStatusStale } from "@/hooks/useGlobalNotifications";
import { deviceCountLabel, globalFailureReasonLabel } from "@/lib/labels";
import type { GlobalNotification } from "@/types/global-notification";

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" });
}

/**
 * Status of the last send of the session. `notification` undefined = not listed yet (just
 * submitted): shown as in progress.
 */
export function SendStatusCard({
  notification,
  estimate,
  onDismiss,
  now,
}: {
  notification: GlobalNotification | undefined;
  estimate: number;
  onDismiss?: () => void;
  /** Current time (ms), ticked by the page: compared with the scheduled time. */
  now: number;
}) {
  const targeted = notification?.recipientCount ?? estimate;
  const targetedLabel = `${deviceCountLabel(targeted)} ${targeted > 1 ? "ciblés" : "ciblé"}`;
  let tone = "border-blue-200 bg-blue-50 text-blue-900";
  let text: string;

  if (!notification) {
    text = `Envoi en cours — ${targetedLabel}`;
  } else if (isStatusStale(notification, now)) {
    tone = "border-amber-200 bg-amber-50 text-amber-900";
    text = "Statut inconnu, actualisez";
  } else if (isSendPending(notification)) {
    text =
      notification.status === "SCHEDULED" && new Date(notification.scheduledAt).getTime() > now
        ? `Programmé le ${formatDateTime(notification.scheduledAt)} — ${targetedLabel}`
        : `Envoi en cours — ${targetedLabel}`;
  } else if (notification.status === "SENT") {
    tone = "border-green-200 bg-green-50 text-green-900";
    text = `✅ Envoyé — ${notification.deliveredCount ?? 0} livrés / ${notification.failureCount ?? 0} échecs`;
  } else if (notification.status === "CANCELLED") {
    tone = "border-gray-200 bg-gray-50 text-gray-700";
    text = "Envoi annulé";
  } else {
    tone = "border-red-200 bg-red-50 text-red-800";
    const reason = globalFailureReasonLabel(notification.failureReason);
    text = reason ? `Échec de l'envoi — ${reason}` : "Échec de l'envoi";
  }

  return (
    <div role="status" className={`flex items-center justify-between gap-4 border rounded-lg px-4 py-3 text-sm ${tone}`}>
      <span>{text}</span>
      {onDismiss && (
        <button type="button" onClick={onDismiss} className="text-xs underline">
          Fermer
        </button>
      )}
    </div>
  );
}
