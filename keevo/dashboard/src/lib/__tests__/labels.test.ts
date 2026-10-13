import { describe, expect, it } from "vitest";
import {
  conflictTypeLabel,
  deviceNumberLabels,
  deviceUserLabel,
  entityTypeLabel,
  humanizeCode,
  isCode,
  operationLabel,
  planLabel,
  rejectionReasonLabel,
  strategyLabel,
  syncStatusLabel,
  userRoleLabel,
} from "@/lib/labels";

describe("labels — known codes", () => {
  it("maps the codes of the 9-8 matrix to French labels", () => {
    expect(operationLabel("CREATE_SALE")).toBe("Enregistrement de vente");
    expect(rejectionReasonLabel("PRODUCT_NOT_FOUND")).toBe("Produit introuvable");
    expect(strategyLabel("LAST_WRITE_WINS")).toBe("Dernière modification gagnante");
    expect(planLabel("PREMIUM_TRIAL")).toBe("Essai Premium");
    expect(planLabel("PREMIUM")).toBe("Premium");
    expect(planLabel("FREE")).toBe("Gratuit");
  });

  it("maps conflict, entity, role and status codes", () => {
    expect(conflictTypeLabel("STOCK_NEGATIVE")).toBe("Stock devenu négatif");
    expect(entityTypeLabel("PRODUCT")).toBe("Produit");
    expect(entityTypeLabel("stock_level")).toBe("Stock");
    expect(entityTypeLabel("UPDATE_CLIENT")).toBe("Modification de client");
    expect(userRoleLabel("OWNER")).toBe("Propriétaire");
    expect(syncStatusLabel("ERROR")).toBe("En erreur");
  });
});

describe("labels — unknown codes and free text", () => {
  it("humanizes an unknown code, never returns it raw", () => {
    expect(humanizeCode("NEW_THING")).toBe("New thing");
    expect(operationLabel("NEW_THING")).toBe("New thing");
    expect(rejectionReasonLabel("NEW_THING")).toBe("New thing");
    expect(planLabel("GOLD_PLAN")).toBe("Gold plan");
  });

  it("never renders a free-text reason (it may contain an id)", () => {
    expect(rejectionReasonLabel("Product not found: 3f2b1c4d-0000-4000-8000-000000000001")).toBe(
      "Données refusées par le serveur",
    );
  });

  it("trims and upper-cases a rejection reason before lookup", () => {
    expect(rejectionReasonLabel("  PRODUCT_NOT_FOUND ")).toBe("Produit introuvable");
    expect(rejectionReasonLabel("product_not_found")).toBe("Produit introuvable");
    expect(rejectionReasonLabel("hello")).toBe("Données refusées par le serveur");
  });

  it("is own-property safe (no prototype keys)", () => {
    for (const key of ["constructor", "__proto__", "toString", "hasOwnProperty"]) {
      expect(typeof planLabel(key)).toBe("string");
      expect(planLabel(key)).not.toMatch(/function|\[object/);
      expect(rejectionReasonLabel(key)).toBe("Données refusées par le serveur");
      expect(operationLabel(key)).not.toMatch(/function|\[object/);
      expect(entityTypeLabel(key)).not.toMatch(/function|\[object/);
    }
    expect(planLabel("constructor")).toBe("Constructor");
  });

  it("never treats an id-like token as a code", () => {
    expect(isCode("NEW_THING")).toBe(true);
    expect(isCode("STOCK_LEVEL_V2")).toBe(true);
    expect(isCode("AB12CD34EF")).toBe(false);
    expect(isCode("A3F2B1C4D")).toBe(false);
    expect(isCode("3F2B1C4D")).toBe(false);
    expect(isCode("DEVICE_3F2B1C")).toBe(false);
    expect(rejectionReasonLabel("AB12CD34EF")).toBe("Données refusées par le serveur");
    expect(operationLabel("ab12cd34ef")).toBe("Opération");
  });

  it("uses a neutral fallback for null or empty values", () => {
    expect(rejectionReasonLabel(null)).toBe("Raison inconnue");
    expect(planLabel(null)).toBe("—");
    expect(operationLabel("")).toBe("Opération");
  });
});

describe("device labels", () => {
  it("prefers the employee name, then the owner role, then « Utilisateur inconnu »", () => {
    expect(deviceUserLabel("Awa Diallo", "EMPLOYEE")).toBe("Awa Diallo");
    expect(deviceUserLabel(null, "OWNER")).toBe("Propriétaire");
    expect(deviceUserLabel("  ", null)).toBe("Utilisateur inconnu");
    expect(deviceUserLabel(null, "EMPLOYEE")).toBe("Utilisateur inconnu");
  });

  it("numbers each user's devices by a stable key, not by display order", () => {
    const a = { deviceId: "aaa", userId: "u1" };
    const b = { deviceId: "bbb", userId: "u1" };
    const c = { deviceId: "ccc", userId: "u2" };
    expect(deviceNumberLabels([a, c, b])).toEqual(["Appareil 1", "Appareil 1", "Appareil 2"]);
    // Backend re-sorted by last push: each device keeps its number.
    expect(deviceNumberLabels([b, a, c])).toEqual(["Appareil 2", "Appareil 1", "Appareil 1"]);
  });
});
