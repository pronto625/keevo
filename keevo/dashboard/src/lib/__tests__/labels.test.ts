import { describe, expect, it } from "vitest";
import {
  auditActionLabel,
  auditEntityTypeLabel,
  deviceCountLabel,
  feedbackTypeLabel,
  globalFailureReasonLabel,
  globalNotificationStatusLabel,
  globalTargetLabel,
  lastActivityDayLabel,
  planFilterLabel,
  platformLabel,
  priorityLabel,
  screenLabel,
  storeTypeLabel,
} from "@/lib/labels";
import {
  conflictTypeLabel,
  deviceNumberLabels,
  deviceUserLabel,
  entityTypeLabel,
  humanizeCode,
  isCode,
  operationLabel,
  paymentMethodLabel,
  planLabel,
  rejectionReasonLabel,
  strategyLabel,
  tenantStatusLabel,
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
    expect(conflictTypeLabel("LAST_WRITE_SUPERSEDED")).toBe(
      "Modification hors ligne ignorée (version plus récente conservée)",
    );
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

describe("plan management labels (Story 9-2)", () => {
  it("labels plans, tenant statuses and payment methods in French", () => {
    expect(planLabel("FREE")).toBe("Gratuit");
    expect(planLabel("PREMIUM_TRIAL")).toBe("Essai Premium");
    expect(planLabel("PREMIUM")).toBe("Premium");
    expect(tenantStatusLabel("ACTIVE")).toBe("Actif");
    expect(tenantStatusLabel("SUSPENDED")).toBe("Suspendu");
    expect(tenantStatusLabel("DELETION_PENDING")).toBe("Suppression planifiée");
    expect(tenantStatusLabel(null)).toBe("—");
    expect(paymentMethodLabel("MOBILE_MONEY")).toBe("Mobile Money");
    expect(paymentMethodLabel("CASH")).toBe("Espèces");
    expect(paymentMethodLabel("BANK_TRANSFER")).toBe("Virement bancaire");
    expect(paymentMethodLabel("OTHER")).toBe("Autre");
    expect(paymentMethodLabel("CRYPTO_WALLET")).toBe("Crypto wallet");
  });
});

describe("labels — spec 9-1 completion", () => {
  it("audit actions mirror the app labels; unknown codes are humanized", () => {
    expect(auditActionLabel("PRICE_CHANGED")).toBe("Prix modifié");
    expect(auditActionLabel("PRODUCT_CREATED")).toBe("Produit créé");
    expect(auditActionLabel("STOCK_THRESHOLD_BREACHED")).toBe("Alerte de stock bas");
    expect(auditActionLabel("BRAND_NEW_ACTION")).toBe("Brand new action");
    expect(auditActionLabel("3f2b1c4d-0000-4000-8000-0000000000aa")).toBe("Action");
    expect(auditActionLabel(null)).toBe("Action");
  });

  it("audit entity types, store types, feedback type / priority / platform", () => {
    expect(auditEntityTypeLabel("Product")).toBe("Produit");
    expect(auditEntityTypeLabel("StockLevel")).toBe("Niveau de stock");
    expect(auditEntityTypeLabel("GiftCard")).toBe("Gift card");
    expect(auditEntityTypeLabel("x#1")).toBe("Élément");
    expect(storeTypeLabel("WAREHOUSE")).toBe("Entrepôt");
    expect(feedbackTypeLabel("bug")).toBe("Problème signalé");
    expect(feedbackTypeLabel("PRAISE")).toBe("Compliment");
    expect(feedbackTypeLabel("free text")).toBe("Autre");
    expect(feedbackTypeLabel("OTHER")).toBe("Autre");
    expect(feedbackTypeLabel("FEATURE_REQUEST")).toBe("Autre");
    expect(priorityLabel("HIGH")).toBe("Haute");
    expect(platformLabel("android")).toBe("Android");
    expect(planFilterLabel("ALL")).toBe("Tous");
    expect(planFilterLabel("PAID")).toBe("Premium et essai");
    expect(planFilterLabel("PREMIUM_TRIAL")).toBe("Essai Premium");
  });

  it("screen label drops ids and translates route segments", () => {
    expect(screenLabel("/products/3f2b1c4d-0000-4000-8000-0000000000aa/edit")).toBe("Produits › Modification");
    expect(screenLabel("/pos/sales-history?x=1")).toBe("Caisse › Historique des ventes");
    expect(screenLabel("/")).toBe("Accueil");
    expect(screenLabel(null)).toBe("—");
  });

  it("last activity day: Aujourd'hui / Hier / date / Jamais", () => {
    const today = new Date(2026, 9, 13, 15, 0);
    expect(lastActivityDayLabel("2026-10-13", today)).toBe("Aujourd'hui");
    expect(lastActivityDayLabel("2026-10-12", today)).toBe("Hier");
    expect(lastActivityDayLabel("2026-09-30", today)).toBe("30/09/2026");
    expect(lastActivityDayLabel(null, today)).toBe("Jamais");
  });

  it("global push: target and status labels (Story 9-5)", () => {
    expect(globalTargetLabel("ALL")).toBe("Tous");
    expect(globalTargetLabel("PREMIUM")).toBe("Premium");
    expect(globalTargetLabel("TRIAL")).toBe("Essai");
    expect(globalTargetLabel("FREE")).toBe("Gratuit");
    expect(globalTargetLabel("TENANT", "Boutique Awa")).toBe("Boutique Awa");
    expect(globalTargetLabel("TENANT", null)).toBe("Entreprise supprimée");
    expect(globalNotificationStatusLabel("SCHEDULED")).toBe("Programmé");
    expect(globalNotificationStatusLabel("SENDING")).toBe("Envoi en cours");
    expect(globalNotificationStatusLabel("SENT")).toBe("Envoyé");
    expect(globalNotificationStatusLabel("FAILED")).toBe("Échec de l'envoi");
    expect(globalNotificationStatusLabel("CANCELLED")).toBe("Annulé");
    expect(globalFailureReasonLabel("PUSH_UNAVAILABLE")).toBe("Notifications push non configurées");
    expect(globalFailureReasonLabel("ALL_CHUNKS_FAILED")).toBe("Échec d'envoi vers Firebase");
    expect(globalFailureReasonLabel("INTERRUPTED")).toBe("Envoi interrompu");
    expect(globalFailureReasonLabel("NO_RECIPIENT")).toBe("Aucun appareil ciblé");
    expect(globalFailureReasonLabel(null)).toBeNull();
    expect(deviceCountLabel(1)).toBe("1 appareil");
    expect(deviceCountLabel(3)).toBe("3 appareils");
  });
});
