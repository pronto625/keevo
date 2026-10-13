import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { GeographySection } from "@/components/analytics/GeographySection";

describe("GeographySection", () => {
  it("labels countries in French (Autre / Inconnu), never the raw code", () => {
    render(
      <GeographySection
        isLoading={false}
        data={[
          { country: "CM", tenantCount: 3 },
          { country: "OTHER", tenantCount: 1 },
          { country: "UNKNOWN", tenantCount: 1 },
        ]}
      />,
    );
    expect(screen.getByText("Cameroun")).toBeInTheDocument();
    expect(screen.getByText("Autre")).toBeInTheDocument();
    expect(screen.getByText("Inconnu")).toBeInTheDocument();
    expect(screen.queryByText("CM")).not.toBeInTheDocument();
    expect(screen.getByText("(60 %)")).toBeInTheDocument();
  });
});
