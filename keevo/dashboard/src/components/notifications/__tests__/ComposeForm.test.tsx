import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ComposeForm, validateCompose } from "@/components/notifications/ComposeForm";
import { getRecipientEstimate, searchPushTenants } from "@/lib/api/admin-notifications";

vi.mock("@/lib/api/admin-notifications", () => ({
  getRecipientEstimate: vi.fn(),
  searchPushTenants: vi.fn(),
}));

const estimate = vi.mocked(getRecipientEstimate);
const search = vi.mocked(searchPushTenants);

function renderForm(
  onSubmit = vi.fn().mockResolvedValue(undefined),
  serverFieldErrors?: Record<string, string>,
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ComposeForm onSubmit={onSubmit} isSubmitting={false} serverFieldErrors={serverFieldErrors} />
    </QueryClientProvider>,
  );
  return onSubmit;
}

describe("ComposeForm (Story 9-5 — global push)", () => {
  beforeEach(() => {
    estimate.mockReset().mockResolvedValue(5);
    search.mockReset().mockResolvedValue([]);
  });

  it("shows live counters 80 / 200 and the text in both previews", async () => {
    const user = userEvent.setup();
    renderForm();

    expect(screen.getByText("0 / 80")).toBeInTheDocument();
    expect(screen.getByText("0 / 200")).toBeInTheDocument();
    expect(screen.getAllByText("Titre de l'annonce")).toHaveLength(2);

    await user.type(screen.getByLabelText("Titre"), "Maintenance");
    await user.type(screen.getByLabelText("Message"), "Ce soir");

    expect(screen.getByText("11 / 80")).toBeInTheDocument();
    expect(screen.getByText("7 / 200")).toBeInTheDocument();
    for (const name of ["Aperçu Android", "Aperçu iOS"]) {
      const preview = screen.getByRole("region", { name });
      expect(within(preview).getByText("Maintenance")).toBeInTheDocument();
      expect(within(preview).getByText("Ce soir")).toBeInTheDocument();
    }
    expect(await screen.findByText("5 appareils ciblés")).toBeInTheDocument();
  });

  it("blocks an 81-character title and an empty message before any confirmation", async () => {
    const user = userEvent.setup();
    const onSubmit = renderForm();

    await user.type(screen.getByLabelText("Titre"), "x".repeat(81));
    await screen.findByText("5 appareils ciblés");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));

    expect(screen.getByText("81 / 80")).toHaveClass("text-red-600");
    expect(screen.getByText("80 caractères maximum")).toBeInTheDocument();
    expect(screen.getByText("Le message est obligatoire")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("asks « Envoyer à N appareils ? » then sends now", async () => {
    const user = userEvent.setup();
    const onSubmit = renderForm();

    await user.type(screen.getByLabelText("Titre"), "Maintenance");
    await user.type(screen.getByLabelText("Message"), "Ce soir 22h");
    await screen.findByText("5 appareils ciblés");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Envoyer à 5 appareils ?")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Confirmer l'envoi" }));

    expect(onSubmit).toHaveBeenCalledWith({
      title: "Maintenance",
      body: "Ce soir 22h",
      target: "ALL",
      targetTenantId: null,
      scheduledAt: null,
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByLabelText("Titre")).toHaveValue("");
  });

  it("« Un commerce » needs a company picked by name", async () => {
    const user = userEvent.setup();
    search.mockResolvedValue([{ id: "3f2b1c4d-0000-4000-8000-0000000000aa", name: "Boutique Awa" }]);
    const onSubmit = renderForm();

    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Tous", "Premium", "Essai", "Gratuit", "Un commerce",
    ]);
    await user.type(screen.getByLabelText("Titre"), "T");
    await user.type(screen.getByLabelText("Message"), "B");
    await user.selectOptions(screen.getByLabelText("Cible"), "TENANT");
    expect(screen.getByText("Choisissez l'entreprise pour estimer les appareils ciblés")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Envoyer" })).toBeDisabled();

    await user.type(screen.getByLabelText("Entreprise"), "Awa");
    await user.click(await screen.findByRole("button", { name: "Boutique Awa" }));
    expect(screen.queryByText(/3f2b1c4d/)).not.toBeInTheDocument();
    await waitFor(() =>
      expect(estimate).toHaveBeenCalledWith("TENANT", "3f2b1c4d-0000-4000-8000-0000000000aa"),
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "Envoyer" })).toBeEnabled());

    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirmer l'envoi" }));
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ target: "TENANT", targetTenantId: "3f2b1c4d-0000-4000-8000-0000000000aa" }),
    );
  });

  it("a failed send keeps the form", async () => {
    const user = userEvent.setup();
    renderForm(vi.fn().mockRejectedValue(new Error("422")));

    await user.type(screen.getByLabelText("Titre"), "Maintenance");
    await user.type(screen.getByLabelText("Message"), "Ce soir");
    await screen.findByText("5 appareils ciblés");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirmer l'envoi" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByLabelText("Titre")).toHaveValue("Maintenance");
  });
});

describe("ComposeForm — estimate, dialog and server errors (Story 9-5)", () => {
  beforeEach(() => {
    estimate.mockReset();
    search.mockReset().mockResolvedValue([]);
  });

  it("« Envoyer » stays disabled until the estimate is loaded", async () => {
    let resolve: (n: number) => void = () => {};
    estimate.mockReturnValue(new Promise<number>((r) => (resolve = r)));
    renderForm();
    expect(screen.getByRole("button", { name: "Envoyer" })).toBeDisabled();
    resolve(3);
    await waitFor(() => expect(screen.getByRole("button", { name: "Envoyer" })).toBeEnabled());
  });

  it("the dialog focuses its confirm button and closes with Escape", async () => {
    estimate.mockResolvedValue(2);
    const user = userEvent.setup();
    const onSubmit = renderForm();
    await user.type(screen.getByLabelText("Titre"), "T");
    await user.type(screen.getByLabelText("Message"), "B");
    await screen.findByText("2 appareils ciblés");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));

    expect(screen.getByRole("button", { name: "Confirmer l'envoi" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("a server field error disappears once its field is edited", async () => {
    estimate.mockResolvedValue(2);
    const user = userEvent.setup();
    renderForm(vi.fn(), { title: "Titre refusé", body: "Message refusé" });
    expect(screen.getByText("Titre refusé")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Titre"), "x");

    expect(screen.queryByText("Titre refusé")).not.toBeInTheDocument();
    expect(screen.getByText("Message refusé")).toBeInTheDocument();
  });

  it("the date picker is limited to the next 30 days", async () => {
    estimate.mockResolvedValue(2);
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByLabelText("Programmer"));
    const input = screen.getByLabelText("Date et heure d'envoi");
    const min = new Date(input.getAttribute("min")!).getTime();
    const max = new Date(input.getAttribute("max")!).getTime();
    expect(Math.round((max - min) / (24 * 3600_000))).toBe(30);
    expect(Math.abs(min - Date.now())).toBeLessThan(120_000);
  });
});

describe("validateCompose", () => {
  const now = new Date("2026-10-13T10:00:00").getTime();
  const base = { title: "T", body: "B", target: "ALL" as const, tenant: null, mode: "later" as const };

  it("scheduled date: required, not past, at most 30 days ahead", () => {
    expect(validateCompose({ ...base, scheduledLocal: "" }, now).scheduledAt).toBe("Choisissez la date d'envoi");
    expect(validateCompose({ ...base, scheduledLocal: "2026-10-13T09:00" }, now).scheduledAt).toBe(
      "La date d'envoi est déjà passée",
    );
    expect(validateCompose({ ...base, scheduledLocal: "2026-11-13T10:00" }, now).scheduledAt).toBe(
      "30 jours maximum à l'avance",
    );
    expect(validateCompose({ ...base, scheduledLocal: "2026-10-13T11:00" }, now)).toEqual({});
  });

  it("limits at 80 / 200 accepted", () => {
    expect(
      validateCompose({ ...base, mode: "now", scheduledLocal: "", title: "x".repeat(80), body: "y".repeat(200) }, now),
    ).toEqual({});
    expect(
      validateCompose({ ...base, mode: "now", scheduledLocal: "", title: "x", body: "y".repeat(201) }, now).body,
    ).toBe("200 caractères maximum");
  });
});
