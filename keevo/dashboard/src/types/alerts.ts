// Story 9-5 — platform alerts feed (GET /api/v1/admin/alerts).

export type AlertSeverity = "HIGH" | "MEDIUM" | "LOW";

/**
 * One alert, computed by the server. `key` is opaque: only used to mark the alert handled
 * (request path) and as React key — never displayed.
 */
export interface AdminAlert {
  key: string;
  /** Severity code (labelled with alertSeverityLabel). */
  severity: AlertSeverity | string;
  /** French title. */
  title: string;
  /** French description naming the company. */
  description: string;
  occurredAt: string | null;
  handled: boolean;
  handledAt: string | null;
}
