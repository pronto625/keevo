import 'dart:async';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/providers.dart';
import '../provider/product_provider.dart';
import '../provider/category_provider.dart';
import '../provider/csv_import_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../domain/model/category_model.dart';
import '../../domain/model/product_model.dart';
import '../widget/draft_validation_banner.dart';
import '../widget/product_card.dart';

enum CatalogTab { active, archived, outOfStock, lowStock }
/// CatalogPage — product catalogue with search and 4 tabs.
///
/// Tabs: Actifs | Archivés | Rupture | Stock Faible
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
  Timer? _debounce;
  late final StateController<String> _queryController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 4, vsync: this);
    // The search query outlives this page (global provider): show it in the field
    // when the page is rebuilt (back from a product, tab switch), otherwise the list
    // stays filtered behind an empty-looking field that cannot be cleared.
    _queryController = ref.read(productSearchQueryProvider.notifier);
    _searchController.text = _queryController.state;

    // Trigger a background sync when the page first opens.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(productActionsProvider).syncFromRemote().ignore();
    });
  }

  @override
  void dispose() {
    // A pending debounced query (e.g. the field was just cleared) must not be lost
    // when the page goes away.
    if (_debounce?.isActive ?? false) {
      _debounce!.cancel();
      final pending = _searchController.text;
      // Provider writes are not allowed during dispose: defer to the next microtask.
      final controller = _queryController;
      Future.microtask(() => controller.state = pending);
    }
    _tabController.dispose();
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String query) {
    _debounce?.cancel();
    // Clearing restores the full list immediately (no debounce).
    if (query.trim().isEmpty) {
      ref.read(productSearchQueryProvider.notifier).state = '';
      setState(() {}); // hide the clear button
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 300), () {
      if (!mounted) return;
      ref.read(productSearchQueryProvider.notifier).state = query;
    });
    setState(() {}); // show the clear button as soon as there is text
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isEmployee = ref.watch(currentUserRoleProvider) == 'EMPLOYEE';

    final activeStoreId = ref.watch(activeStoreIdProvider);
    final storesAsync = ref.watch(storeListNotifierProvider);
    final activeStoreName = storesAsync.maybeWhen(
      data: (stores) => activeStoreId == null
          ? null
          : stores.where((s) => s.id == activeStoreId).firstOrNull?.name,
      orElse: () => null,
    );
    
    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // AppBar épuré — fond neutre, plus de bandeau dégradé saturé.
          SliverAppBar(
            expandedHeight: 96,
            floating: false,
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: theme.colorScheme.surface,
            // Show back button when navigated from a notification deep-link.
            leading: context.canPop()
                ? IconButton(
                    icon: Icon(Icons.arrow_back_ios_new_rounded,
                        color: theme.colorScheme.onSurface),
                    onPressed: () => context.pop(),
                  )
                : null,
            automaticallyImplyLeading: false,
            flexibleSpace: FlexibleSpaceBar(
              background: SafeArea(
                bottom: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 8, 16, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'Catalogue',
                                  style: theme.textTheme.headlineMedium?.copyWith(
                                    color: theme.colorScheme.onSurface,
                                    fontWeight: FontWeight.w700,
                                    letterSpacing: -0.3,
                                  ),
                                ),
                                const SizedBox(height: 4),
                                if (activeStoreName != null)
                                  Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Icon(Icons.storefront_outlined,
                                          size: 14,
                                          color: theme.colorScheme.onSurfaceVariant),
                                      const SizedBox(width: 4),
                                      Text(
                                        activeStoreName,
                                        style: theme.textTheme.bodyMedium?.copyWith(
                                          color: theme.colorScheme.onSurfaceVariant,
                                        ),
                                      ),
                                    ],
                                  )
                                else
                                  Text(
                                    'Gérez vos produits facilement',
                                    style: theme.textTheme.bodyMedium?.copyWith(
                                      color: theme.colorScheme.onSurfaceVariant,
                                    ),
                                  ),
                              ],
                            ),
                          ),
                          // Vue Stock multi-boutiques (Story 3.2)
                          SoftIconButton(
                            icon: Icons.warehouse_outlined,
                            tooltip: 'Vue stock multi-boutiques',
                            onPressed: () => context.push('/stock/overview'),
                          ),
                          const SizedBox(width: 6),
                          // Bouton de synchronisation
                          SoftIconButton(
                            icon: Icons.sync_rounded,
                            tooltip: 'Synchroniser',
                            onPressed: () async {
                              await ref.read(productActionsProvider).syncFromRemote();
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(
                                    content: Row(
                                      children: [
                                        const Icon(Icons.check_circle, color: AppTheme.onBrand),
                                        const SizedBox(width: 8),
                                        const Text('Catalogue synchronisé'),
                                      ],
                                    ),
                                    backgroundColor: AppTheme.successStrong,
                                    behavior: SnackBarBehavior.floating,
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(12),
                                    ),
                                  ),
                                );
                              }
                            },
                          ),
                          const SizedBox(width: 6),
                          // Menu "..." — CSV import + catégories (OWNER only)
                          if (!isEmployee)
                            Container(
                              decoration: BoxDecoration(
                                color: AppTheme.softTint(Theme.of(context).brightness),
                                borderRadius: BorderRadius.circular(14),
                              ),
                              child: PopupMenuButton<String>(
                                icon: Icon(Icons.more_vert, color: theme.colorScheme.primary),
                                onSelected: (value) {
                                  if (value == 'import') {
                                    context.push('/products/import');
                                  } else if (value == 'template') {
                                    _downloadCsvTemplate(context);
                                  } else if (value == 'categories') {
                                    _showCategoriesBottomSheet(context);
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
                                  const PopupMenuItem(
                                    value: 'template',
                                    child: ListTile(
                                      leading: Icon(Icons.download_rounded),
                                      title: Text('Télécharger le modèle CSV'),
                                      contentPadding: EdgeInsets.zero,
                                    ),
                                  ),
                                  const PopupMenuItem(
                                    value: 'categories',
                                    child: ListTile(
                                      leading: Icon(Icons.category_rounded),
                                      title: Text('Gérer les catégories'),
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
          // Barre de recherche — bordure fine plutôt qu'ombre portée.
          SliverToBoxAdapter(
            child: Container(
              margin: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: theme.colorScheme.surface,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: theme.colorScheme.outlineVariant),
              ),
              child: TextField(
                controller: _searchController,
                decoration: InputDecoration(
                  hintText: 'Rechercher par nom, SKU…',
                  hintStyle: TextStyle(
                    color: theme.colorScheme.onSurfaceVariant.withOpacity(0.7),
                  ),
                  prefixIcon: Icon(
                    Icons.search_rounded,
                    color: theme.colorScheme.onSurfaceVariant,
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
                    borderRadius: BorderRadius.circular(16),
                    borderSide: BorderSide.none,
                  ),
                  filled: false,
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 14,
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
                // Toggle drafts-only filter and switch to the Actifs tab.
                final current = ref.read(showDraftsOnlyProvider);
                ref.read(showDraftsOnlyProvider.notifier).state = !current;
                _tabController.animateTo(0);
              },
            ),
          ),
          // Active drafts filter indicator
          if (ref.watch(showDraftsOnlyProvider))
            SliverToBoxAdapter(
              child: Container(
                margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                child: Row(
                  children: [
                    Chip(
                      avatar: const Icon(Icons.filter_alt_rounded,
                          size: 18, color: AppTheme.warning),
                      label: const Text('Brouillons uniquement'),
                      deleteIcon: const Icon(Icons.close, size: 18),
                      onDeleted: () {
                        ref.read(showDraftsOnlyProvider.notifier).state = false;
                      },
                      backgroundColor: AppTheme.warning.withOpacity(0.12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(20),
                        side: BorderSide(color: AppTheme.warning.withOpacity(0.3)),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          // Low stock filter indicator
          if (ref.watch(showLowStockOnlyProvider))
            SliverToBoxAdapter(
              child: Container(
                margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                child: Row(
                  children: [
                    Chip(
                      avatar: const Icon(Icons.warning_rounded,
                          size: 18, color: AppTheme.errorColor),
                      label: const Text('Stock bas uniquement'),
                      deleteIcon: const Icon(Icons.close, size: 18),
                      onDeleted: () {
                        ref.read(showLowStockOnlyProvider.notifier).state = false;
                      },
                      backgroundColor: AppTheme.errorColor.withOpacity(0.12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(20),
                        side: BorderSide(color: AppTheme.errorColor.withOpacity(0.3)),
                      ),
                    ),
                  ],
                ),
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
                _ProductListView(tab: CatalogTab.active),
                _ProductListView(tab: CatalogTab.archived),
                _ProductListView(tab: CatalogTab.outOfStock),
                _ProductListView(tab: CatalogTab.lowStock),
              ],
            ),
          ),
        ],
      ),
      // FAB "Ajouter un produit" — OWNER + EMPLOYEE
      // Couleur pleine + ombre douce plutôt qu'un dégradé + ombre marquée.
      floatingActionButton: Container(
        decoration: BoxDecoration(
          color: theme.colorScheme.primary,
          borderRadius: BorderRadius.circular(20),
          boxShadow: [
            BoxShadow(
              color: theme.colorScheme.primary.withOpacity(0.25),
              blurRadius: 12,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: FloatingActionButton.extended(
          onPressed: () => context.push('/products/new'),
          backgroundColor: Colors.transparent,
          elevation: 0,
          icon: Icon(Icons.add_rounded, color: theme.colorScheme.onPrimary),
          label: Text(
            'Ajouter un produit',
            style: TextStyle(
              color: theme.colorScheme.onPrimary,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }

  // ── CSV template quick download ─────────────────────────────────────────────

  Future<void> _downloadCsvTemplate(BuildContext context) async {
    final success =
        await ref.read(csvImportNotifierProvider.notifier).downloadTemplate();
    if (!mounted || success) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Impossible de télécharger le modèle — connexion requise'),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  // ── Category management bottom sheet ───────────────────────────────────────

  void _showCategoriesBottomSheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      // La feuille dessine sa propre poignée ; évite la double poignée du thème.
      showDragHandle: false,
      backgroundColor: Theme.of(context).colorScheme.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (ctx) => _CategoryManagerSheet(parentRef: ref),
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
              color: theme.colorScheme.shadow.withOpacity(0.06),
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
        ],
      ),
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.4),
          borderRadius: BorderRadius.circular(16),
        ),
        child: TabBar(
          controller: tabController,
          isScrollable: true,
          tabAlignment: TabAlignment.start,
          // Couleur pleine plutôt qu'un dégradé — plus sobre, cohérent avec
          // le reste du rebrand (plus de dégradés saturés sur les contrôles).
          indicator: BoxDecoration(
            color: theme.colorScheme.primary,
            borderRadius: BorderRadius.circular(12),
          ),
          indicatorSize: TabBarIndicatorSize.tab,
          dividerColor: Colors.transparent,
          labelColor: theme.colorScheme.onPrimary,
          unselectedLabelColor: theme.colorScheme.onSurfaceVariant,
          labelStyle: const TextStyle(
            fontWeight: FontWeight.w600,
            fontSize: 13,
          ),
          unselectedLabelStyle: const TextStyle(
            fontWeight: FontWeight.w500,
            fontSize: 13,
          ),
          tabs: [
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.inventory_2_rounded, size: 16),
                  const SizedBox(width: 6),
                  const Text('Actifs'),
                ],
              ),
            ),
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.archive_rounded, size: 16),
                  const SizedBox(width: 6),
                  const Text('Archivés'),
                ],
              ),
            ),
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.remove_shopping_cart_outlined, size: 16, color: AppTheme.errorColor),
                  const SizedBox(width: 6),
                  const Text('Rupture'),
                ],
              ),
            ),
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.warning_amber_rounded, size: 16, color: AppTheme.warning),
                  const SizedBox(width: 6),
                  const Text('Stock Bas'),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  bool shouldRebuild(covariant SliverPersistentHeaderDelegate oldDelegate) => true;
}

class _ProductListView extends ConsumerWidget {
  final CatalogTab tab;

  const _ProductListView({required this.tab});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<List<ProductModel>> listAsync = switch (tab) {
      CatalogTab.active   => ref.watch(productListProvider),
      CatalogTab.archived => ref.watch(archivedProductListProvider),
      CatalogTab.outOfStock => ref.watch(outOfStockProductListProvider),
      CatalogTab.lowStock   => ref.watch(lowStockProductListProvider),
    };

    return listAsync.when(
      skipLoadingOnReload: true,
      loading: () =>
          const Center(child: CircularProgressIndicator()),
      // skipLoadingOnReload keeps the error during a retry: show the
      // spinner instead so the tap gets visible feedback.
      error: (error, _) => listAsync.isLoading
          ? const Center(child: CircularProgressIndicator())
          : AppErrorWidget(
              error: error,
              onRetry: () => ref.invalidate(switch (tab) {
                CatalogTab.active => productListProvider,
                CatalogTab.archived => archivedProductListProvider,
                CatalogTab.outOfStock => outOfStockProductListProvider,
                CatalogTab.lowStock => lowStockProductListProvider,
              }),
            ),
      data: (products) {
        if (products.isEmpty) {
          final query = ref.watch(productSearchQueryProvider);
          final showSearchEmpty = tab == CatalogTab.active && query.isNotEmpty;

          return Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  showSearchEmpty
                      ? Icons.search_off_rounded
                      : switch (tab) {
                          CatalogTab.active     => Icons.inventory_2_outlined,
                          CatalogTab.archived   => Icons.archive_outlined,
                          CatalogTab.outOfStock => Icons.remove_shopping_cart_outlined,
                          CatalogTab.lowStock   => Icons.warning_amber_rounded,
                        },
                  size: 64,
                  color: Theme.of(context).colorScheme.outlineVariant,
                ),
                const SizedBox(height: 16),
                Text(
                  showSearchEmpty
                      ? 'Aucun résultat pour «\u202F$query\u202F»'
                      : switch (tab) {
                          CatalogTab.active     => 'Aucun produit dans le catalogue',
                          CatalogTab.archived   => 'Aucun produit archivé',
                          CatalogTab.outOfStock => 'Aucun produit en rupture de stock',
                          CatalogTab.lowStock   => 'Aucun produit en stock bas',
                        },
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                if (showSearchEmpty) ...[
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    onPressed: () => context.push('/products/new'),
                    icon: const Icon(Icons.add_rounded),
                    label: const Text('Créer un produit'),
                  ),
                ] else if (!showSearchEmpty && tab == CatalogTab.active) ...[
                  const SizedBox(height: 8),
                  const Text('Appuyez sur + pour ajouter votre premier produit'),
                ] else if (tab == CatalogTab.archived) ...[
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
              if (tab == CatalogTab.active && products.isNotEmpty)
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

// ── Category manager bottom sheet ────────────────────────────────────────────

class _CategoryManagerSheet extends ConsumerStatefulWidget {
  final WidgetRef parentRef;
  const _CategoryManagerSheet({required this.parentRef});

  @override
  ConsumerState<_CategoryManagerSheet> createState() =>
      _CategoryManagerSheetState();
}

class _CategoryManagerSheetState
    extends ConsumerState<_CategoryManagerSheet> {
  final _nameController = TextEditingController();
  bool _loading = false;

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  Future<void> _createCategory() async {
    final name = _nameController.text.trim();
    if (name.isEmpty) return;
    setState(() => _loading = true);
    try {
      await ref.read(categoryActionsProvider).create(name);
      _nameController.clear();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Catégorie « $name » créée'),
            backgroundColor: AppTheme.successStrong,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(appErrorMessage(e)), backgroundColor: AppTheme.errorStrong),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _renameCategory(CategoryModel cat) async {
    final controller = TextEditingController(text: cat.name);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Renommer la catégorie'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Nouveau nom'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Annuler')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Renommer')),
        ],
      ),
    );
    if (confirmed == true && controller.text.trim().isNotEmpty) {
      try {
        await ref
            .read(categoryActionsProvider)
            .rename(cat.id, controller.text.trim());
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
                content: Text(appErrorMessage(e)), backgroundColor: AppTheme.errorStrong),
          );
        }
      }
    }
    // Defer dispose until the dialog's exit animation completes — disposing
    // inline causes "controller used after dispose" when the TextField inside
    // the dialog route is still alive during its pop animation.
    WidgetsBinding.instance.addPostFrameCallback((_) => controller.dispose());
  }

  Future<void> _deleteCategory(CategoryModel cat) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Supprimer la catégorie'),
        content: Text(
          '« ${cat.name} » sera désactivée. Les produits associés ne seront pas supprimés.',
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Annuler')),
          FilledButton(
            style:
                FilledButton.styleFrom(
                  backgroundColor: AppTheme.errorStrong,
                  foregroundColor: AppTheme.onBrand,
                ),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      try {
        await ref.read(categoryActionsProvider).delete(cat.id);
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
                content: Text(appErrorMessage(e)), backgroundColor: AppTheme.errorStrong),
          );
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final categoriesAsync = ref.watch(categoriesProvider);

    return DraggableScrollableSheet(
      initialChildSize: 0.7,
      minChildSize: 0.4,
      maxChildSize: 0.95,
      expand: false,
      builder: (ctx, scrollCtrl) => Column(
        children: [
          // Handle
          Container(
            margin: const EdgeInsets.symmetric(vertical: 12),
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: theme.colorScheme.onSurfaceVariant.withOpacity(0.3),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Icon(Icons.category_rounded,
                    color: theme.colorScheme.primary, size: 24),
                const SizedBox(width: 12),
                Text('Gérer les catégories',
                    style: theme.textTheme.titleLarge
                        ?.copyWith(fontWeight: FontWeight.w600)),
              ],
            ),
          ),
          const SizedBox(height: 16),
          // Add category input
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _nameController,
                    decoration: InputDecoration(
                      hintText: 'Nom de la nouvelle catégorie',
                      border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(16)),
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: 16, vertical: 12),
                    ),
                    onSubmitted: (_) => _createCategory(),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.icon(
                  onPressed: _loading ? null : _createCategory,
                  icon: _loading
                      ? SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Theme.of(context).colorScheme.onSurfaceVariant))
                      : const Icon(Icons.add, size: 18),
                  label: const Text('Ajouter'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          const Divider(height: 1),
          // Category list
          Expanded(
            child: categoriesAsync.when(
              loading: () =>
                  const Center(child: CircularProgressIndicator()),
              error: (e, _) => AppErrorWidget(
                error: e,
                onRetry: () => ref.invalidate(categoriesProvider),
              ),
              data: (categories) {
                if (categories.isEmpty) {
                  return Center(
                    child: Text(
                      'Aucune catégorie',
                      style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant),
                    ),
                  );
                }
                return ListView.separated(
                  controller: scrollCtrl,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                  itemCount: categories.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, i) {
                    final cat = categories[i];
                    return ListTile(
                      leading: CircleAvatar(
                        backgroundColor:
                            theme.colorScheme.primaryContainer,
                        child: Text(
                          cat.name.isNotEmpty
                              ? cat.name[0].toUpperCase()
                              : '?',
                          style: TextStyle(
                              color:
                                  theme.colorScheme.onPrimaryContainer),
                        ),
                      ),
                      title: Text(cat.name),
                      subtitle: cat.isCustom
                          ? null
                          : Text('Catégorie système',
                              style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme
                                      .onSurfaceVariant)),
                      trailing: cat.isCustom
                          ? Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                IconButton(
                                  icon: const Icon(Icons.edit_rounded,
                                      size: 20),
                                  color: theme.colorScheme.primary,
                                  tooltip: 'Renommer',
                                  onPressed: () => _renameCategory(cat),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.delete_outline,
                                      size: 20),
                                  color: AppTheme.errorColor,
                                  tooltip: 'Supprimer',
                                  onPressed: () => _deleteCategory(cat),
                                ),
                              ],
                            )
                          : IconButton(
                              icon: const Icon(Icons.edit_rounded,
                                  size: 20),
                              color: theme.colorScheme.primary,
                              tooltip: 'Renommer',
                              onPressed: () => _renameCategory(cat),
                            ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
