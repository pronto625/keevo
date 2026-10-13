import { afterEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import {
  getFeedback,
  getUnhandledHighCount,
  markFeedbackHandled,
  markFeedbackUnhandled,
} from "@/lib/api/admin-feedback";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const get = vi.mocked(client.get);
const post = vi.mocked(client.post);

describe("admin-feedback API", () => {
  afterEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it("badge count uses the count-only endpoint", async () => {
    get.mockResolvedValue({ data: { data: 7 } });

    await expect(getUnhandledHighCount()).resolves.toBe(7);
    expect(get).toHaveBeenCalledWith("/admin/feedback/count", { params: { priority: "HIGH", handled: "false" } });
  });

  it("list omits empty type / priority and maps the pagination meta", async () => {
    get.mockResolvedValue({
      data: { data: [{ id: "a" }], meta: { page: 2, size: 20, totalElements: 41, totalPages: 3 } },
    });

    const page = await getFeedback({ type: "", priority: "", handled: "false" }, 2);

    expect(get).toHaveBeenCalledWith("/admin/feedback", { params: { page: 2, size: 20, handled: "false" } });
    expect(page).toEqual({ items: [{ id: "a" }], page: 2, size: 20, totalElements: 41, totalPages: 3 });
  });

  it("list sends type and priority when set", async () => {
    get.mockResolvedValue({ data: { data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } } });

    await getFeedback({ type: "BUG", priority: "HIGH", handled: "all" }, 0);

    expect(get).toHaveBeenCalledWith("/admin/feedback", {
      params: { page: 0, size: 20, handled: "all", type: "BUG", priority: "HIGH" },
    });
  });

  it("handle / unhandle post to the feedback action URLs", async () => {
    post.mockResolvedValue({ data: { data: { id: "f1", handled: true } } });

    await markFeedbackHandled("f1");
    await markFeedbackUnhandled("f1");

    expect(post).toHaveBeenNthCalledWith(1, "/admin/feedback/f1/handle");
    expect(post).toHaveBeenNthCalledWith(2, "/admin/feedback/f1/unhandle");
  });
});
