import { describe, expect, it } from "vitest";
import { customRangeError } from "@/lib/period";
import { periodLabel } from "@/lib/labels";

describe("customRangeError (mirrors the backend CUSTOM rules)", () => {
  it("accepts an inclusive range up to 366 days", () => {
    expect(customRangeError("2026-01-01", "2026-01-01")).toBeNull();
    expect(customRangeError("2024-01-01", "2024-12-31")).toBeNull(); // 366 days (leap year)
  });

  it("rejects missing, malformed, inverted or too long ranges", () => {
    expect(customRangeError("", "2026-01-01")).not.toBeNull();
    expect(customRangeError("2026-01-01", "")).not.toBeNull();
    expect(customRangeError("01/01/2026", "2026-01-31")).toBe("Dates invalides.");
    // Calendar rollover: 2026-02-31 is not a date (the backend would answer 422).
    expect(customRangeError("2026-02-31", "2026-03-10")).toBe("Dates invalides.");
    expect(customRangeError("2026-01-01", "2026-13-01")).toBe("Dates invalides.");
    expect(customRangeError("2024-02-29", "2024-03-01")).toBeNull(); // leap day
    expect(customRangeError("2026-02-01", "2026-01-01")).toBe("La date de début doit précéder la date de fin.");
    expect(customRangeError("2024-01-01", "2025-01-01")).toBe("La période ne peut pas dépasser 366 jours.");
  });

  it("rejects years outside 2000..9999 like the backend", () => {
    const msg = "Les dates doivent être comprises entre 2000 et 9999.";
    expect(customRangeError("1999-12-31", "2000-01-05")).toBe(msg);
    expect(customRangeError("0001-01-01", "0001-01-02")).toBe(msg);
    expect(customRangeError("2000-01-01", "2000-01-31")).toBeNull();
  });
});

describe("periodLabel", () => {
  it("maps period codes to French labels", () => {
    expect(periodLabel("TODAY")).toBe("Aujourd'hui");
    expect(periodLabel("7D")).toBe("7 jours");
    expect(periodLabel("CUSTOM")).toBe("Personnalisée");
  });
});
