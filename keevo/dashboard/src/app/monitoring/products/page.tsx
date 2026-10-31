import type { Metadata } from "next";
import ProductsPageClient from "./ProductsPageClient";

export const metadata: Metadata = {
  title: "Catalogue Produits",
};

export default function ProductsPage() {
  return <ProductsPageClient />;
}
