import type { Metadata } from "next";
import SantePageClient from "./SantePageClient";

export const metadata: Metadata = {
  title: "Santé système",
};

export default function SantePage() {
  return <SantePageClient />;
}
