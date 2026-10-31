import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import RetoursPageClient from "@/app/retours/RetoursPageClient";
import { getFeedback, markFeedbackHandled, markFeedbackUnhandled } from "@/lib/api/admin-feedback";
import type { AdminFeedback, FeedbackPage } from "@/types/feedback";

vi.mock("@/lib/api/admin-feedback", () => ({
  getFeedback: vi.fn(),
  getUnhandledHighCount: vi.fn(),
  markFeedbackHandled: vi.fn(),
  markFeedbackUnhandled: vi.fn(),
}));

vi.mock("@/components/layout/AdminLayout", () => ({
  AdminLayout: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

const list = vi.mocked(getFeedback);
const handle = vi.mocked(markFeedbackHandled);
const unhandle = vi.mocked(markFeedbackUnhandled);

const FEEDBACK: AdminFeedback = {
  id: "3f2b1c4d-0000-4000-8000-0000000000aa",
  type: "BUG",
  description: "Le scanner plante",
  appVersion: "1.4.2",
  platform: "android",
  screenContext: "/pos",
  submittedAt: "2026-10-12T09:00:00Z",
  priority: "HIGH",
  tenantName: "Boutique Awa",
  userLabel: "Awa Diallo",
  handled: false,
  handledAt: null,
};

function pageOf(items: AdminFeedback[], page = 0, totalElements = items.length): FeedbackPage {
  return { items, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const invalidate = vi.spyOn(client, "invalidateQueries");
  render(
    <QueryClientProvider client={client}>
      <RetoursPageClient />
    </QueryClientProvider>,
  );
  return { client, invalidate };
}

describe("« Retours utilisateurs » page", () => {
  beforeEach(() => {
    list.mockReset().mockResolvedValue(pageOf([FEEDBACK]));
    handle.mockReset().mockResolvedValue({ ...FEEDBACK, handled: true });
    unhandle.mockReset();
  });

  it("defaults to unhandled feedback, page 0", async () => {
    renderPage();
    expect(await screen.findByText("Boutique Awa")).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith({ type: "", priority: "", handled: "false" }, 0);
  });

  it("filters by type, priority and handled state", async () => {
    renderPage();
    await screen.findByText("Boutique Awa");

    await userEvent.selectOptions(screen.getByLabelText("Type"), "BUG");
    await userEvent.selectOptions(screen.getByLabelText("Priorité"), "HIGH");
    await userEvent.selectOptions(screen.getByLabelText("Traité"), "true");

    await waitFor(() =>
      expect(list).toHaveBeenLastCalledWith({ type: "BUG", priority: "HIGH", handled: "true" }, 0),
    );
  });

  it("« Marquer comme traité » posts, refetches the list and invalidates the sidebar count", async () => {
    const { invalidate } = renderPage();
    await screen.findByText("Boutique Awa");
    const calls = list.mock.calls.length;

    await userEvent.click(screen.getByRole("button", { name: "Marquer comme traité" }));

    await waitFor(() => expect(handle).toHaveBeenCalledWith(FEEDBACK.id, expect.anything()));
    await waitFor(() => expect(list.mock.calls.length).toBeGreaterThan(calls));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["admin-feedback-high-count"] });
  });

  it("handling the last item of the last page goes back to the previous page", async () => {
    const page0 = Array.from({ length: 20 }, (_, i) => ({ ...FEEDBACK, id: `id-${i}`, tenantName: `Boutique ${i}` }));
    const last = { ...FEEDBACK, id: "id-last", tenantName: "Boutique dernière" };
    let handled = false;
    list.mockReset().mockImplementation(async (_f, page) => {
      const total = handled ? 20 : 21;
      return page === 0 ? pageOf(page0, 0, total) : pageOf(handled ? [] : [last], page, total);
    });
    renderPage();
    await screen.findByText("Boutique 0");
    await userEvent.click(screen.getByRole("button", { name: "Suivant →" }));
    await screen.findByText("Boutique dernière");

    handle.mockImplementation(async () => {
      handled = true;
      return { ...last, handled: true };
    });
    await userEvent.click(screen.getByRole("button", { name: "Marquer comme traité" }));

    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.anything(), 0));
    expect(await screen.findByText("Boutique 0")).toBeInTheDocument();
  });

  it("an action error is cleared by a later success and by a filter change", async () => {
    const second = { ...FEEDBACK, id: "3f2b1c4d-0000-4000-8000-0000000000bb", tenantName: "Boutique Kane" };
    list.mockReset().mockResolvedValue(pageOf([FEEDBACK, second]));
    handle.mockReset()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValue({ ...second, handled: true });
    renderPage();
    await screen.findByText("Boutique Awa");

    await userEvent.click(screen.getAllByRole("button", { name: "Marquer comme traité" })[0]);
    expect(await screen.findByText(/L'action n'a pas pu être enregistrée/)).toBeInTheDocument();

    await userEvent.click(screen.getAllByRole("button", { name: "Marquer comme traité" })[1]);
    await waitFor(() => expect(screen.queryByText(/L'action n'a pas pu être enregistrée/)).not.toBeInTheDocument());

    handle.mockRejectedValueOnce(new Error("boom"));
    await userEvent.click(screen.getAllByRole("button", { name: "Marquer comme traité" })[0]);
    expect(await screen.findByText(/L'action n'a pas pu être enregistrée/)).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Priorité"), "HIGH");
    await waitFor(() => expect(screen.queryByText(/L'action n'a pas pu être enregistrée/)).not.toBeInTheDocument());
  });

  it("first load failure → « Erreur de chargement » + « Réessayer »", async () => {
    list.mockReset().mockRejectedValueOnce(new Error("boom")).mockResolvedValue(pageOf([FEEDBACK]));
    renderPage();

    expect(await screen.findByText("Erreur de chargement")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(await screen.findByText("Boutique Awa")).toBeInTheDocument();
  });
});
