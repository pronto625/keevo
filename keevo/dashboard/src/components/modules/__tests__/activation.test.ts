import { describe, expect, it } from "vitest";
import { parseLimit, trialHint, validateActivation, validateBundle } from "@/components/modules/activation";

const TODAY = "2026-11-01";

describe("activation form checks (Story 16.4)", () => {
  it("a paid module needs an end date when Actif; Essai without date is the 14-day trial", () => {
    expect(validateActivation({ status: "ACTIVE", expiresAt: null }, true, TODAY)).toBe(
      "Une date de fin est requise pour un module payant",
    );
    expect(validateActivation({ status: "TRIAL", expiresAt: null }, true, TODAY)).toBeNull();
    expect(validateActivation({ status: "ACTIVE", expiresAt: "2027-06-30" }, true, TODAY)).toBeNull();
    expect(validateActivation({ status: "ACTIVE", expiresAt: "2026-10-31" }, true, TODAY)).toBe(
      "La date de fin doit être dans le futur",
    );
    expect(validateActivation({ status: "ACTIVE", expiresAt: null }, false, TODAY)).toBeNull();
  });

  it("trial hint: today + 14 days as JJ/MM/AAAA", () => {
    expect(trialHint(TODAY)).toBe("Sans date : essai de 14 jours, jusqu'au 15/11/2026");
  });

  it("pack form: slug id, name, at most 500 chars of description, at least one module", () => {
    expect(validateBundle("pack-pro", { name: "Pack Pro", description: "", modules: ["pos"] })).toEqual({});
    const errors = validateBundle("Pack Pro", { name: " ", description: "x".repeat(501), modules: [] });
    expect(errors.id).toContain("Minuscules");
    expect(errors.name).toBe("Indiquez le nom du pack");
    expect(errors.description).toBe("500 caractères au maximum");
    expect(errors.modules).toBe("Choisissez au moins un module");
    expect(validateBundle("", { name: "A", description: "", modules: ["pos"] }).id).toBe(
      "Indiquez l'identifiant du pack",
    );
  });

  it("limits: empty = illimité, a positive integer, anything else refused", () => {
    expect(parseLimit("")).toEqual({ value: null, error: null });
    expect(parseLimit(" 500 ")).toEqual({ value: 500, error: null });
    expect(parseLimit("0").error).not.toBeNull();
    expect(parseLimit("-1").error).not.toBeNull();
    expect(parseLimit("2.5").error).not.toBeNull();
  });
});
