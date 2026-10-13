import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SuspendDialog } from "@/components/tenants/SuspendDialog";

function setup(reasonRequired: boolean) {
  const onConfirm = vi.fn();
  render(
    <SuspendDialog
      title="Suspendre ce compte ?"
      description="Lecture seule"
      confirmLabel="Suspendre"
      reasonRequired={reasonRequired}
      isPending={false}
      onConfirm={onConfirm}
      onCancel={vi.fn()}
    />,
  );
  return { onConfirm, user: userEvent.setup() };
}

describe("SuspendDialog", () => {
  it("requires a reason of at least 3 characters when reasonRequired", async () => {
    const { onConfirm, user } = setup(true);
    await user.click(screen.getByRole("button", { name: "Suspendre" }));
    expect(screen.getByText("Motif requis (3 à 500 caractères)")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Motif"), "ab");
    await user.click(screen.getByRole("button", { name: "Suspendre" }));
    expect(onConfirm).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("Motif"), "c fraude ");
    await user.click(screen.getByRole("button", { name: "Suspendre" }));
    expect(onConfirm).toHaveBeenCalledWith("abc fraude");
  });

  it("accepts an empty reason when optional", async () => {
    const { onConfirm, user } = setup(false);
    expect(screen.getByLabelText("Motif (facultatif)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Suspendre" }));
    expect(onConfirm).toHaveBeenCalledWith("");
  });
});
