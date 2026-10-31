import type { Metadata } from "next";
import AlertesPageClient from "./AlertesPageClient";

export const metadata: Metadata = {
  title: "Alertes",
};

export default function AlertesPage() {
  return <AlertesPageClient />;
}
