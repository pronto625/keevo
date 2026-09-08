---
title: 'Fix: produit ajouté dans une boutique invisible du catalogue des autres boutiques'
type: 'bugfix'
created: '2026-09-08'
status: 'done'
route: 'one-shot'
---

## Intent

**Problem:** Un produit ajouté dans une boutique apparaît correctement en caisse (POS) des autres boutiques avec un stock à 0, mais reste invisible dans leur onglet Catalogue dès qu'une autre boutique a déjà enregistré du stock pour ce produit — empêchant tout mouvement de stock sur ce produit dans ces boutiques.

**Approach:** Supprimer le filtre de visibilité par boutique (basé sur l'existence d'une ligne `stock_levels`) dans `productList`, `outOfStockProductList` et `lowStockProductList` — le catalogue liste désormais tout produit actif/brouillon pour chaque boutique, comme le fait déjà le POS. En contrepartie, scoper `getLowStockProductIds()` par boutique (il ne l'était pas) pour qu'un produit critique ailleurs ne s'affiche pas faussement comme "stock faible" dans une boutique qui n'en a jamais eu.

## Suggested Review Order

**Suppression du filtre de visibilité par boutique**

- Point d'entrée : le catalogue liste tout produit actif/brouillon du tenant, sans filtre par boutique.
  [`product_provider.dart:102`](../../keevo/app/lib/features/catalog/presentation/provider/product_provider.dart#L102)

- Onglet "Rupture de stock" : mêmes produits, stock recalculé par boutique (0 si aucune ligne `stock_levels` ici).
  [`product_provider.dart:177`](../../keevo/app/lib/features/catalog/presentation/provider/product_provider.dart#L177)

**Correction du scope boutique de "stock faible" (bug latent révélé par la suppression du filtre)**

- `getLowStockProductIds()` accepte désormais un `storeId` optionnel pour ne compter que la ligne de la boutique active.
  [`local_product_datasource.dart:74`](../../keevo/app/lib/features/catalog/data/datasource/local_product_datasource.dart#L74)

- Les deux appelants (`productList` filtre rapide, `lowStockProductList`) passent `activeStoreId`.
  [`product_provider.dart:111`](../../keevo/app/lib/features/catalog/presentation/provider/product_provider.dart#L111)
  [`product_provider.dart:218`](../../keevo/app/lib/features/catalog/presentation/provider/product_provider.dart#L218)

**Tests**

- Régression bout-en-bout : un produit stocké uniquement dans `store-a` reste visible (catalogue, rupture) et n'est pas faussement "stock faible" dans `store-b`.
  [`product_provider_test.dart:111`](../../keevo/app/test/features/catalog/presentation/provider/product_provider_test.dart#L111)

- Couverture du scope `storeId` de `getLowStockProductIds()` au niveau datasource.
  [`local_product_datasource_test.dart:177`](../../keevo/app/test/features/catalog/data/datasource/local_product_datasource_test.dart#L177)
