import type { Metadata } from "next";
import RevenusPageClient from "./RevenusPageClient";

export const metadata: Metadata = {
  title: "Revenus",
};

export default function RevenusPage() {
  return <RevenusPageClient />;
}
