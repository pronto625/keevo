import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { IncidentBanner } from "@/components/health/IncidentBanner";
import { useHealthStatus } from "@/hooks/useHealth";

// The status query's error state is driven directly: its retries (2) are a hook setting.
vi.mock("@/hooks/useHealth", () => ({ useHealthStatus: vi.fn() }));

const hook = vi.mocked(useHealthStatus);

describe("IncidentBanner — status unreachable (Story 9-5)", () => {
  it("shows « État du serveur inconnu » when the status cannot be read after retries", () => {
    hook.mockReturnValue({ data: undefined, isError: true } as ReturnType<typeof useHealthStatus>);
    render(<IncidentBanner />);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "État du serveur inconnu — le tableau de bord ne parvient pas à joindre le serveur.",
    );
  });

  it("an outage hides a stale « no incident » answer", () => {
    hook.mockReturnValue({
      data: { incident: false, breachedMetrics: [] },
      isError: true,
    } as unknown as ReturnType<typeof useHealthStatus>);
    render(<IncidentBanner />);
    expect(screen.getByRole("alert")).toHaveTextContent("État du serveur inconnu");
  });
});
