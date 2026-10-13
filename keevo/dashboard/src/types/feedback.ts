// Spec 9-1 completion — « Retours utilisateurs » (GET /api/v1/admin/feedback).

export type FeedbackPriority = "HIGH" | "NORMAL";

/** One feedback. `id` is used for the actions / keys only — never displayed. */
export interface AdminFeedback {
  id: string;
  /** Type code (labelled with feedbackTypeLabel). */
  type: string;
  description: string;
  appVersion: string | null;
  /** Platform code (labelled with platformLabel). */
  platform: string | null;
  /** App route (labelled with screenLabel). */
  screenContext: string | null;
  submittedAt: string;
  priority: FeedbackPriority | string;
  /** Business name, « Entreprise supprimée » when the company is gone. */
  tenantName: string;
  /** Employee name, « Propriétaire » or « Utilisateur inconnu ». */
  userLabel: string;
  handled: boolean;
  handledAt: string | null;
}

export interface FeedbackFilters {
  /** "" = all types. */
  type: string;
  /** "" = all priorities. */
  priority: "" | FeedbackPriority;
  /** Default « Non traités ». */
  handled: "false" | "true" | "all";
}

export interface FeedbackPage {
  items: AdminFeedback[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
