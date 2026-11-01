import type { Metadata } from "next";
import PlansPageClient from "./PlansPageClient";

export const metadata: Metadata = {
  title: "Profils & plafonds",
};

export default function PlansPage() {
  return <PlansPageClient />;
}
