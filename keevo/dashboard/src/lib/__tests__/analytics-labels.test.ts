import { describe, expect, it } from "vitest";
import { cohortWeekLabel, countryLabel, featureLabel } from "@/lib/labels";
import { lastDaysRange, plusDays, revenueRangeError, topStoresRangeError } from "@/lib/period";

describe("Story 9-4 labels", () => {
  it("countryLabel maps ISO codes, OTHER/unmapped → Autre, empty/UNKNOWN → Inconnu", () => {
    expect(countryLabel("CI")).toBe("Côte d'Ivoire");
    expect(countryLabel("CD")).toBe("RD Congo");
    expect(countryLabel("OTHER")).toBe("Autre");
    expect(countryLabel("ZZ")).toBe("Autre");
    expect(countryLabel("UNKNOWN")).toBe("Inconnu");
    expect(countryLabel(null)).toBe("Inconnu");
  });

  it("featureLabel gives French names, humanizes unknown codes", () => {
    expect(featureLabel("WHATSAPP_REPORTS")).toBe("Rapports WhatsApp");
    expect(featureLabel("OFFLINE_SYNC")).toBe("Synchronisation hors ligne");
    expect(featureLabel("NEW_THING")).toBe("New thing");
  });

  it("cohortWeekLabel", () => {
    expect(cohortWeekLabel("2026-09-28")).toBe("Sem. du 28/09/2026");
    expect(cohortWeekLabel("")).toBe("—");
  });
});

describe("Story 9-4 period helpers", () => {
  it("plusDays crosses months and years", () => {
    expect(plusDays("2026-10-13", -29)).toBe("2026-09-14");
    expect(plusDays("2026-12-31", 1)).toBe("2027-01-01");
  });

  it("topStoresRangeError: year bound 2000..9999 (same message as 9-3), order, 366 days", () => {
    expect(topStoresRangeError("2026-09-14", "2026-10-13")).toBeNull();
    expect(topStoresRangeError("1999-12-31", "2000-01-02")).toBe("Les dates doivent être comprises entre 2000 et 9999.");
    expect(topStoresRangeError("1999-12-31", "2000-01-02")).toBe(revenueRangeError("1999-12-31", "2000-01-02"));
    expect(topStoresRangeError("2026-02-01", "2026-01-01")).toBe("La date de début doit précéder la date de fin.");
    expect(topStoresRangeError("2024-01-01", "2025-01-01")).toBe("La période ne peut pas dépasser 366 jours.");
    expect(topStoresRangeError("", "2026-01-01")).toBe("Choisissez une date de début et une date de fin.");
    expect(topStoresRangeError("2026-02-31", "2026-03-01")).toBe("Dates invalides.");
  });

  it("lastDaysRange uses the Douala calendar day", () => {
    // 2026-10-12 23:30 UTC = 2026-10-13 00:30 in Douala
    expect(lastDaysRange(30, new Date("2026-10-12T23:30:00Z"))).toEqual({ from: "2026-09-14", to: "2026-10-13" });
  });
});
