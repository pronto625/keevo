import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import client from "@/lib/api/client";
import { churnCsvFilename, downloadChurnCsv } from "@/lib/api/admin-revenue";

vi.mock("@/lib/api/client", () => ({ default: { get: vi.fn() } }));

describe("downloadChurnCsv", () => {
  const createObjectURL = vi.fn(() => "blob:churn");
  const revokeObjectURL = vi.fn();

  beforeEach(() => {
    Object.assign(URL, { createObjectURL, revokeObjectURL });
  });

  afterEach(() => {
    vi.mocked(client.get).mockReset();
  });

  it("names the file keevo-churn-YYYY-MM.csv", () => {
    expect(churnCsvFilename("2026-09")).toBe("keevo-churn-2026-09.csv");
  });

  it("fetches the export with the JWT client and saves it under the French filename", async () => {
    const blob = new Blob(["﻿Entreprise;Fin du plan"], { type: "text/csv" });
    vi.mocked(client.get).mockResolvedValue({ data: blob });
    let downloaded: { href: string; download: string } | null = null;
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloaded = { href: this.href, download: this.download };
    });

    vi.useFakeTimers();
    try {
      await downloadChurnCsv("2026-09");
      // Not revoked synchronously (would cancel the download), only on the next tick.
      expect(revokeObjectURL).not.toHaveBeenCalled();
      vi.runAllTimers();
    } finally {
      vi.useRealTimers();
    }

    expect(client.get).toHaveBeenCalledWith("/admin/revenue/churn/export", {
      params: { month: "2026-09" },
      responseType: "blob",
    });
    expect(click).toHaveBeenCalledTimes(1);
    expect(downloaded).toEqual({ href: "blob:churn", download: "keevo-churn-2026-09.csv" });
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:churn");
  });
});
