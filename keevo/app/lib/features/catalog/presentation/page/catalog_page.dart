import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../provider/product_provider.dart';
import '../widget/draft_validation_banner.dart';
import '../widget/product_card.dart';

/// CatalogPage — product catalogue with search and tabs (Active / Archivés).
///
/// AC1: FAB "Ajouter un produit" opens [ProductFormPage] for creation.
/// AC6: "Archivés" tab shows archived products.
/// AC7: search bar filters in real-time (debounce 300ms) against local Drift DB.
class CatalogPage extends ConsumerStatefulWidget {
  const CatalogPage({super.key});

  @override
  ConsumerState<CatalogPage> createState() => _CatalogPageState();
}

class _CatalogPageState extends ConsumerState<CatalogPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  final _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);

    // Trigger a background sync when the page first opens.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(productActionsProvider).syncFromRemote().ignore();
    });
  }

  @override
  void dispose() {
    _tabController.dispose();
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String query) {
    // Debounce is handled by Riverpod invalidation cadence.
    ref.read(productSearchQueryProvider.notifier).state = query;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    
    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // AppBar moderne avec gradient
          SliverAppBar(
            expandedHeight: 160,
            floating: false,
            pinned: true,
            elevation: 0,
            backgroundColor: Colors.transparent,
            flexibleSpace: FlexibleSpaceBar(
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      theme.colorScheme.primary,
                      theme.colorScheme.primary.withOpacity(0.8),
                    ],
                  ),
                ),
                child: SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '📦 Catalogue',
                                    style: theme.textTheme.headlineMedium?.copyWith(
                                      color: Colors.white,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    'Gérez vos produits facilement',
                                    style: theme.textTheme.bodyMedium?.copyWith(
                                      color: Colors.white.withOpacity(0.9),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            // Vue Stock multi-boutiques (Story 3.2)
                            Container(
                              decoration: BoxDecoration(
                                color: Colors.white.withOpacity(0.2),
                                borderRadius: BorderRadius.circular(16),
                              ),
                              child: IconButton(
                                icon: const Icon(Icons.warehouse_outlined,
                                    color: Colors.white),
                                tooltip: 'Vue stock multi-boutiques',
                                onPressed: () =>
                                    context.push('/stock/overview'),
                              ),
                            ),
                            const SizedBox(width: 4),
                            // Bouton de synchronisation moderne
                            Container(
                              decoration: BoxDecoration(
                                color: Colors.white.withOpacity(0.2),
                                borderRadius: BorderRadius.circular(16),
                              ),
                              child: IconButton(
                                onPressed: () async {
                                  await ref.read(productActionsProvider).syncFromRemote();
                                  if (context.mounted) {
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      SnackBar(
                                        content: Row(
                                          children: [
                                            Icon(Icons.check_circle, color: Colors.white),
                                            const SizedBox(width: 8),
                                            const Text('Catalogue synchronisé'),
                                          ],
                                        ),
                                        backgroundColor: Colors.green,
                                        behavior: SnackBarBehavior.floating,
                                        shape: RoundedRectangleBorder(
                                          borderRadius: BorderRadius.circular(12),
                                        ),
                                      ),
                                    );
                                  }
                                },
                                icon: const Icon(Icons.sync_rounded, color: Colors.white),
                                tooltip: 'Synchroniser',
                              ),
                            ),
                            const SizedBox(width: 4),
                            // Menu "..." — CSV import (AC1/AC5)
                            Container(
                              decoration: BoxDecoration(
                                color: Colors.white.withOpacity(0.2),
                                borderRadius: BorderRadius.circular(16),
                              ),
                              child: PopupMenuButton<String>(
                                icon: const Icon(Icons.more_vert, color: Colors.white),
                                onSelected: (value) {
                                  if (value == 'import') {
                                    context.push('/products/import');
                                  }
                                },
                                itemBuilder: (_) => [
                                  const PopupMenuItem(
                                    value: 'import',
                                    child: ListTile(
                                      leading: Icon(Icons.upload_file),
                                      title: Text('Importer CSV'),
                                      contentPadding: EdgeInsets.zero,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
          // Barre de recherche moderne
          SliverToBoxAdapter(
            child: Container(
              margin: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: theme.colorScheme.surface,
                borderRadius: BorderRadius.circular(20),
                boxShadow: [
                  BoxShadow(
                    color: theme.colorScheme.shadow.withOpacity(0.1),
                    blurRadius: 12,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: TextField(
                controller: _searchController,
                decoration: InputDecoration(
                  hintText: '🔍 Rechercher par nom, SKU…',
                  hintStyle: TextStyle(
                    color: theme.colorScheme.onSurfaceVariant.withOpacity(0.7),
                  ),
                  prefixIcon: Icon(
                    Icons.search_rounded,
                    color: theme.colorScheme.primary,
                  ),
                  suffixIcon: _searchController.text.isNotEmpty
                      ? IconButton(
                          icon: Icon(
                            Icons.clear_rounded,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                          onPressed: () {
                            _searchController.clear();
                            _onSearchChanged('');
                          },
                        )
                      : null,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(20),
                    borderSide: BorderSide.none,
                  ),
                  filled: true,
                  fillColor: theme.colorScheme.surfaceVariant.withOpacity(0.3),
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 16,
                  ),
                ),
                onChanged: _onSearchChanged,
              ),
            ),
          ),
          // DraftValidationBanner — AC10: amber alert for pending drafts (Owner only)
          SliverToBoxAdapter(
            child: DraftValidationBanner(
              onTap: () {
                // Filter product list to show only DRAFT products.
                // For now, show a tab switch cue — full filtering in Epic 4.
                ref.read(productSearchQueryProvider.notifier).state = '';
                _tabController.animateTo(0);
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text(
                        '🔶 Filtrez par "brouillon" pour voir les produits à valider'),
                    behavior: SnackBarBehavior.floating,
                    duration: Duration(seconds: 3),
                  ),
                );
              },
            ),
          ),
          // TabBar moderne
          SliverPersistentHeader(
            pinned: true,
            delegate: _ModernTabDelegate(
              tabController: _tabController,
              theme: theme,
            ),
          ),
          // Contenu des onglets
          SliverFillRemaining(
            child: TabBarView(
              controller: _tabController,
              children: const [
                _ProductListView(archived: false),
                _ProductListView(archived: true),
              ],
            ),
          ),
        ],
      ),
      // FAB moderne
      floatingActionButton: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              theme.colorScheme.primary,
              theme.colorScheme.primary.withOpacity(0.8),
            ],
          ),
          borderRadius: BorderRadius.circular(20),
          boxShadow: [
            BoxShadow(
              color: theme.colorScheme.primary.withOpacity(0.4),
              blurRadius: 16,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: FloatingActionButton.extended(
          onPressed: () => context.push('/products/new'),
          backgroundColor: Colors.transparent,
          elevation: 0,
          icon: const Icon(Icons.add_rounded, color: Colors.white),
          label: Text(
            'Ajouter un produit',
            style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }
}

// Delegate pour TabBar moderne et collant
class _ModernTabDelegate extends SliverPersistentHeaderDelegate {
  final TabController tabController;
  final ThemeData theme;

  _ModernTabDelegate({
    required this.tabController,
    required this.theme,
  });

  @override
  double get minExtent => 60;

  @override
  double get maxExtent => 60;

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) {
    return Container(
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        boxShadow: [
          if (overlapsContent)
            BoxShadow(
              color: theme.colorScheme.shadow.withOpacity(0.1),
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
        ],
      ),
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceVariant.withOpacity(0.5),
          borderRadius: BorderRadius.circular(16),
        ),
        child: TabBar(
          controller: tabController,
          indicator: BoxDecoration(
            gradient: LinearGradient(
              colors: [
                theme.colorScheme.primary,
                theme.colorScheme.primary.withOpacity(0.8),
              ],
            ),
            borderRadius: BorderRadius.circular(12),
          ),
          indicatorSize: TabBarIndicatorSize.tab,
          dividerColor: Colors.transparent,
          labelColor: Colors.white,
          unselectedLabelColor: theme.colorScheme.onSurfaceVariant,
          labelStyle: const TextStyle(
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
          unselectedLabelStyle: const TextStyle(
            fontWeight: FontWeight.w500,
            fontSize: 14,
          ),
          tabs: [
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.inventory_2_rounded, size: 18),
                  const SizedBox(width: 8),
                  const Text('Actifs'),
                ],
              ),
            ),
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.archive_rounded, size: 18),
                  const SizedBox(width: 8),
                  const Text('Archivés'),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  bool shouldRebuild(covariant SliverPersistentHeaderDelegate oldDelegate) => false;
}

class _ProductListView extends ConsumerWidget {
  final bool archived;

  const _ProductListView({required this.archived});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final listAsync = archived
        ? ref.watch(archivedProductListProvider)
        : ref.watch(productListProvider);

    return listAsync.when(
      loading: () =>
          const Center(child: CircularProgressIndicator()),
      error: (error, _) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 48, color: Colors.red),
            const SizedBox(height: 12),
            Text(
              'Erreur : $error',
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
      data: (products) {
        if (products.isEmpty) {
          return Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  archived ? Icons.archive_outlined : Icons.inventory_2_outlined,
                  size: 64,
                  color: Theme.of(context).colorScheme.outlineVariant,
                ),
                const SizedBox(height: 16),
                Text(
                  archived
                      ? 'Aucun produit archivé'
                      : 'Aucun produit dans le catalogue',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                if (!archived) ...[
                  const SizedBox(height: 8),
                  const Text('Appuyez sur + pour ajouter votre premier produit'),
                ] else ...[
                  const SizedBox(height: 8),
                  const Text('Les produits archivés apparaîtront ici'),
                ],
              ],
            ),
          );
        }

        return RefreshIndicator(
          onRefresh: () async {
            await ref.read(productActionsProvider).syncFromRemote();
          },
          child: Column(
            children: [
              // Message d'aide pour l'appui long (seulement dans l'onglet actifs et s'il y a des produits)
              if (!archived && products.isNotEmpty)
                Container(
                  width: double.infinity,
                  margin: const EdgeInsets.all(16),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: Theme.of(context).colorScheme.primary.withOpacity(0.3),
                    ),
                  ),
                  child: Row(
                    children: [
                      Icon(
                        Icons.touch_app,
                        color: Theme.of(context).colorScheme.primary,
                        size: 20,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          'Conseil : Maintenez un produit enfoncé pour accéder aux options (archiver, modifier...)',
                          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).colorScheme.primary,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              // Liste des produits
              Expanded(
                child: ListView.builder(
                  padding: const EdgeInsets.only(top: 8, bottom: 80),
                  itemCount: products.length,
                  itemBuilder: (_, i) => ProductCard(product: products[i]),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
