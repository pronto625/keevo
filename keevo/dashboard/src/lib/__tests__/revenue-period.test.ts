import { describe, expect, it } from "vitest";
import { currentMonth, isValidMonth, monthRange, plusMonths, revenueRangeError, todayInDouala } from "@/lib/period";
import { monthLabel } from "@/lib/labels";

describe("revenue range helpers (Story 9-3, mirrors the backend rules)", () => {
  it("accepts a valid range up to 24 months", () => {
    expect(revenueRangeError("2026-10-01", "2026-10-31")).toBeNull();
    expect(revenueRangeError("2025-01-01", "2026-12-31")).toBeNull();
  });

  it("rejects missing, invalid, inverted and > 24 months ranges", () => {
    expect(revenueRangeError("", "2026-10-31")).toMatch(/Choisissez/);
    expect(revenueRangeError("2026-02-31", "2026-03-01")).toBe("Dates invalides.");
    expect(revenueRangeError("2026-11-01", "2026-10-01")).toMatch(/précéder/);
    expect(revenueRangeError("2025-01-01", "2027-01-01")).toMatch(/24 mois/);
  });

  it("month helpers", () => {
    expect(monthRange("2026-02")).toEqual({ from: "2026-02-01", to: "2026-02-28" });
    expect(monthRange("2024-02")).toEqual({ from: "2024-02-01", to: "2024-02-29" });
    expect(currentMonth(new Date("2026-10-13T10:00:00Z"))).toBe("2026-10");
    expect(isValidMonth("2026-09")).toBe(true);
    expect(isValidMonth("2026-13")).toBe(false);
    expect(isValidMonth("")).toBe(false);
  });

  it("monthLabel in French", () => {
    expect(monthLabel("2026-10")).toBe("octobre 2026");
    expect(monthLabel("2026-02", true)).toBe("févr. 26");
    expect(monthLabel("bad")).toBe("—");
  });

  it("today / current month follow Africa/Douala (UTC+1), not the browser clock", () => {
    // 23:30 UTC on 31/10 is already 00:30 on 01/11 in Douala.
    const instant = new Date("2026-10-31T23:30:00Z");
    expect(todayInDouala(instant)).toBe("2026-11-01");
    expect(currentMonth(instant)).toBe("2026-11");
  });

  it("24-month limit clamps the day like Java plusMonths (29 Feb)", () => {
    expect(plusMonths("2024-02-29", 24)).toBe("2026-02-28");
    expect(plusMonths("2026-01-31", 1)).toBe("2026-02-28");
    // Java: to < 2026-02-28 accepted, 2026-02-28 rejected.
    expect(revenueRangeError("2024-02-29", "2026-02-27")).toBeNull();
    expect(revenueRangeError("2024-02-29", "2026-02-28")).toMatch(/24 mois/);
  });

  it("rejects years outside 2000..9999 with a French message", () => {
    expect(revenueRangeError("1999-12-01", "2000-01-31")).toBe("Les dates doivent être comprises entre 2000 et 9999.");
    expect(revenueRangeError("2026-01-01", "2026-01-31")).toBeNull();
  });
});
