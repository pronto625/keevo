import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PlanProfileCard } from "@/components/modules/PlanProfileCard";
import { BundleForm } from "@/components/modules/BundleForm";
import type { CatalogueModule, PlanProfile } from "@/types/modules";

const PAID: CatalogueModule[] = [
  { moduleId: "exports", displayName: "Exports PDF et Excel", category: "premium", free: false, version: "1.1.0", requires: [], migrations: false },
  { moduleId: "invoicing", displayName: "Facturation", category: "premium", free: false, version: "1.4.0", requires: [], migrations: true },
];

const FREE: PlanProfile = {
  planType: "FREE", label: "Gratuit", maxStores: 1, maxProducts: 500, maxEmployees: 2, modules: [], fallback: false,
};

describe("PlanProfileCard (Story 16.4)", () => {
  it("shows « illimité » for empty limits and the fallback warning", () => {
    render(
      <PlanProfileCard
        profile={{ ...FREE, planType: "PREMIUM", label: "Premium", maxStores: null, maxProducts: null,
          maxEmployees: null, fallback: true }}
        paidModules={PAID} isPending={false} serverError={null} onSave={vi.fn()}
      />,
    );
    expect(screen.getByText("Boutiques : illimité · Produits : illimité · Employés : illimité")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("valeurs par défaut");
  });

  it("saves the three limits explicitly (empty = null) and the checked paid modules; refuses 0", async () => {
    const onSave = vi.fn();
    const user = userEvent.setup();
    render(<PlanProfileCard profile={FREE} paidModules={PAID} isPending={false} serverError={null} onSave={onSave} />);
    const form = screen.getByRole("form", { name: "Offre Gratuit" });
    const [stores, products] = within(form).getAllByRole("textbox");

    await user.clear(stores);
    await user.type(stores, "0");
    await user.click(within(form).getByRole("button", { name: "Enregistrer" }));
    expect(onSave).not.toHaveBeenCalled();
    expect(within(form).getByText("Nombre entier supérieur à zéro, ou vide pour illimité")).toBeInTheDocument();

    await user.clear(stores);
    await user.type(stores, "2");
    await user.clear(products);
    await user.click(within(form).getByRole("button", { name: "Enregistrer" }));
    expect(onSave).toHaveBeenLastCalledWith({ maxStores: 2, maxProducts: null, maxEmployees: 2, modules: [] });
    expect(screen.queryByRole("alertdialog")).toBeNull(); // limits only: no confirmation
  });

  it("a change of the granted modules asks first; « Annuler » sends nothing, « Continuer » saves", async () => {
    const onSave = vi.fn();
    const user = userEvent.setup();
    render(<PlanProfileCard profile={FREE} paidModules={PAID} isPending={false} serverError={null} onSave={onSave} />);
    await user.click(screen.getByRole("checkbox", { name: "Exports PDF et Excel" }));
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));

    const confirm = screen.getByRole("alertdialog");
    expect(confirm).toHaveTextContent(
      "Tous les comptes de l'offre « Gratuit » seront mis à jour et leurs propriétaires seront prévenus des modules désactivés. Continuer ?",
    );
    await user.click(within(confirm).getByRole("button", { name: "Annuler" }));
    expect(onSave).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Continuer" }));
    expect(onSave).toHaveBeenCalledWith({ maxStores: 1, maxProducts: 500, maxEmployees: 2, modules: ["exports"] });
  });

  it("a granted module no longer paid / installed is dropped with a visible note", async () => {
    const onSave = vi.fn();
    const user = userEvent.setup();
    render(
      <PlanProfileCard
        profile={{ ...FREE, modules: [{ id: "invoicing", displayName: "Facturation" }, { id: "gone", displayName: "Ancien module" }] }}
        paidModules={PAID} isPending={false} serverError={null} onSave={onSave}
      />,
    );
    expect(screen.getByText(/seront retirés à l'enregistrement : Ancien module/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Continuer" }));
    expect(onSave).toHaveBeenCalledWith({ maxStores: 1, maxProducts: 500, maxEmployees: 2, modules: ["invoicing"] });
  });
});

describe("BundleForm (Story 16.4)", () => {
  it("validates the new pack, then sends id, name, description and modules", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<BundleForm bundle={null} modules={PAID} isPending={false} serverError={null} onSubmit={onSubmit} onCancel={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText("Indiquez l'identifiant du pack")).toBeInTheDocument();
    expect(screen.getByText("Choisissez au moins un module")).toBeInTheDocument();

    await user.type(screen.getByPlaceholderText("pack-pro"), "pack-export");
    const [, name] = screen.getAllByRole("textbox");
    await user.type(name, "Pack Export");
    await user.click(screen.getByRole("checkbox", { name: /Exports PDF et Excel/ }));
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    expect(onSubmit).toHaveBeenCalledWith("pack-export", {
      name: "Pack Export", description: "", modules: ["exports"],
    });
  });

  it("a module of the pack no longer offered shows as « Module indisponible », checked, and can be unticked", async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(
      <BundleForm
        bundle={{ id: "pro", name: "Pack Pro", description: null, applicable: false,
          modules: [{ moduleId: "invoicing", displayName: "Facturation" }, { moduleId: "gone", displayName: "Module indisponible" }] }}
        modules={PAID} isPending={false} serverError={null} onSubmit={onSubmit} onCancel={vi.fn()}
      />,
    );
    const gone = screen.getByRole("checkbox", { name: "Module indisponible" });
    expect(gone).toBeChecked();
    await user.click(gone);
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    expect(onSubmit).toHaveBeenCalledWith("pro", { name: "Pack Pro", description: "", modules: ["invoicing"] });
  });

  it("an existing pack keeps its id (not editable) and shows the server's French error", () => {
    render(
      <BundleForm
        bundle={{ id: "pro", name: "Pack Pro", description: null, applicable: true,
          modules: [{ moduleId: "invoicing", displayName: "Facturation" }] }}
        modules={PAID} isPending={false} serverError="Pack invalide : « Socle » fait partie du socle"
        onSubmit={vi.fn()} onCancel={vi.fn()}
      />,
    );
    expect(screen.queryByPlaceholderText("pack-pro")).toBeNull();
    expect(screen.getByRole("alert")).toHaveTextContent("Pack invalide");
    expect(screen.getByRole("checkbox", { name: /Facturation/ })).toBeChecked();
  });
});
