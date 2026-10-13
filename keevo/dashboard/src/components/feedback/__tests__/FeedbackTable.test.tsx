import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FeedbackTable } from "@/components/feedback/FeedbackTable";
import type { AdminFeedback, FeedbackPage } from "@/types/feedback";

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const CODE_PATTERN = /\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\b/;
const ALL_CAPS_PATTERN = /\b[A-Z]{3,}\b/;

function fb(over: Partial<AdminFeedback>): AdminFeedback {
  return {
    id: "3f2b1c4d-0000-4000-8000-0000000000aa",
    type: "BUG",
    description: "Le scanner plante quand je valide la vente",
    appVersion: "1.4.2",
    platform: "android",
    screenContext: "/products/9a8b7c6d-1111-4111-8111-000000000009/edit",
    submittedAt: "2026-10-12T09:00:00Z",
    priority: "HIGH",
    tenantName: "Boutique Awa",
    userLabel: "Awa Diallo",
    handled: false,
    handledAt: null,
    ...over,
  };
}

function page(items: AdminFeedback[]): FeedbackPage {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: 1 };
}

describe("FeedbackTable", () => {
  it("lists merchant names and French labels, never an id or a raw code; HIGH rows highlighted", () => {
    render(
      <FeedbackTable
        data={page([
          fb({}),
          fb({ id: "3f2b1c4d-0000-4000-8000-0000000000bb", type: "suggestion", priority: "NORMAL",
            tenantName: "Entreprise supprimée", userLabel: "Propriétaire", platform: "ios", screenContext: "/pos" }),
        ])}
        isLoading={false}
        page={0}
        onPageChange={vi.fn()}
        onHandle={vi.fn()}
        onUnhandle={vi.fn()}
        pendingIds={new Set()}
      />,
    );

    expect(screen.getByText("Affichage 1–2 sur 2 retours")).toBeInTheDocument();
    expect(screen.getByText("Boutique Awa")).toBeInTheDocument();
    expect(screen.getByText("Entreprise supprimée")).toBeInTheDocument();
    expect(screen.getByText("Problème signalé")).toBeInTheDocument();
    expect(screen.getByText("Suggestion")).toBeInTheDocument();
    expect(screen.getByText("Haute")).toBeInTheDocument();
    expect(screen.getByText("Normale")).toBeInTheDocument();
    expect(screen.getByText("Produits › Modification")).toBeInTheDocument();
    expect(screen.getByText("Caisse")).toBeInTheDocument();
    expect(screen.getByText("1.4.2 · Android")).toBeInTheDocument();
    expect(screen.getByText("1.4.2 · iOS")).toBeInTheDocument();

    const highRow = screen.getByText("Boutique Awa").closest("tr")!;
    expect(highRow.className).toContain("bg-red-50");
    expect(screen.getByText("Entreprise supprimée").closest("tr")!.className).not.toContain("bg-red-50");

    const text = document.body.textContent ?? "";
    expect(text).not.toMatch(UUID_PATTERN);
    expect(text).not.toMatch(CODE_PATTERN);
    expect(text).not.toMatch(ALL_CAPS_PATTERN);
  });

  it("disables the buttons of every feedback whose action is in flight", () => {
    render(
      <FeedbackTable
        data={page([
          fb({}),
          fb({ id: "3f2b1c4d-0000-4000-8000-0000000000bb", tenantName: "Boutique Kane" }),
          fb({ id: "3f2b1c4d-0000-4000-8000-0000000000cc", tenantName: "Boutique Bio" }),
        ])}
        isLoading={false}
        page={0}
        onPageChange={vi.fn()}
        onHandle={vi.fn()}
        onUnhandle={vi.fn()}
        pendingIds={new Set(["3f2b1c4d-0000-4000-8000-0000000000aa", "3f2b1c4d-0000-4000-8000-0000000000cc"])}
      />,
    );
    const buttons = screen.getAllByRole("button", { name: "Marquer comme traité" });
    expect(buttons.map((b) => (b as HTMLButtonElement).disabled)).toEqual([true, false, true]);
  });

  it("« Marquer comme traité » / « Remettre à traiter » call back with the feedback id", async () => {
    const onHandle = vi.fn();
    const onUnhandle = vi.fn();
    render(
      <FeedbackTable
        data={page([
          fb({}),
          fb({ id: "3f2b1c4d-0000-4000-8000-0000000000bb", handled: true, handledAt: "2026-10-13T08:00:00Z" }),
        ])}
        isLoading={false}
        page={0}
        onPageChange={vi.fn()}
        onHandle={onHandle}
        onUnhandle={onUnhandle}
        pendingIds={new Set()}
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "Marquer comme traité" }));
    expect(onHandle).toHaveBeenCalledWith("3f2b1c4d-0000-4000-8000-0000000000aa");
    expect(screen.getByText("Traité le 13/10/2026")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Remettre à traiter" }));
    expect(onUnhandle).toHaveBeenCalledWith("3f2b1c4d-0000-4000-8000-0000000000bb");
  });
});
