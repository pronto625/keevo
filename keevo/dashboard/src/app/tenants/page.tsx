import type { Metadata } from "next";
import TenantsPageClient from "./TenantsPageClient";

export const metadata: Metadata = {
  title: "Entreprises",
};

export default function TenantsPage() {
  return <TenantsPageClient />;
}
