// Story 9-5 (2/2) — global push notifications (GET/POST /api/v1/admin/notifications/global).

export type GlobalNotificationTarget = "ALL" | "PREMIUM" | "TRIAL" | "FREE" | "TENANT";

export type GlobalNotificationStatus = "SCHEDULED" | "SENDING" | "SENT" | "FAILED" | "CANCELLED";

/** One announcement of the history (90 days, newest first). */
export interface GlobalNotification {
  /** Only used for status polling and « Annuler » — never displayed. */
  id: string;
  title: string;
  body: string;
  /** Target code (labelled with globalTargetLabel). */
  target: GlobalNotificationTarget | string;
  /** Business name of the company when target is TENANT. */
  targetTenantName: string | null;
  /** Status code (labelled with globalNotificationStatusLabel). */
  status: GlobalNotificationStatus | string;
  scheduledAt: string;
  createdAt: string;
  sentAt: string | null;
  recipientCount: number | null;
  deliveredCount: number | null;
  failureCount: number | null;
  /** Why a FAILED send failed (labelled with globalFailureReasonLabel), else null. */
  failureReason: string | null;
}

/** POST body. `scheduledAt` null = now (ISO-8601 otherwise). */
export interface GlobalNotificationInput {
  title: string;
  body: string;
  target: GlobalNotificationTarget;
  targetTenantId: string | null;
  scheduledAt: string | null;
}

export interface GlobalNotificationScheduled {
  id: string;
  recipientEstimate: number;
}

/** A company picked in « Un commerce » (id never displayed). */
export interface PickedTenant {
  id: string;
  name: string;
}
