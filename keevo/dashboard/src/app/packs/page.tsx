import type { Metadata } from "next";
import PacksPageClient from "./PacksPageClient";

export const metadata: Metadata = {
  title: "Packs",
};

export default function PacksPage() {
  return <PacksPageClient />;
}
