import client from "./client";
import type { AdminFeedback, FeedbackFilters, FeedbackPage } from "@/types/feedback";

export const FEEDBACK_PAGE_SIZE = 20;

interface PaginatedBody<T> {
  data: T;
  meta?: { page: number; size: number; totalElements: number; totalPages: number };
}

/** GET /admin/feedback — newest first, 20 per page; `handled` defaults to « Non traités ». */
export async function getFeedback(filters: FeedbackFilters, page: number): Promise<FeedbackPage> {
  const params: Record<string, string | number> = { page, size: FEEDBACK_PAGE_SIZE, handled: filters.handled };
  if (filters.type) params.type = filters.type;
  if (filters.priority) params.priority = filters.priority;
  const res = await client.get<PaginatedBody<AdminFeedback[]>>("/admin/feedback", { params });
  const items = res.data.data ?? [];
  const meta = res.data.meta;
  return {
    items,
    page: meta?.page ?? page,
    size: meta?.size ?? FEEDBACK_PAGE_SIZE,
    totalElements: meta?.totalElements ?? items.length,
    totalPages: meta?.totalPages ?? (items.length ? 1 : 0),
  };
}

/** Number of HIGH-priority feedback not handled yet (sidebar badge) — count-only endpoint, no rows. */
export async function getUnhandledHighCount(): Promise<number> {
  const res = await client.get<{ data: number }>("/admin/feedback/count", {
    params: { priority: "HIGH", handled: "false" },
  });
  return Number(res.data.data) || 0;
}

export async function markFeedbackHandled(id: string): Promise<AdminFeedback> {
  const res = await client.post<{ data: AdminFeedback }>(`/admin/feedback/${id}/handle`);
  return res.data.data;
}

export async function markFeedbackUnhandled(id: string): Promise<AdminFeedback> {
  const res = await client.post<{ data: AdminFeedback }>(`/admin/feedback/${id}/unhandle`);
  return res.data.data;
}
