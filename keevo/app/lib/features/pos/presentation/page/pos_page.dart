import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import 'package:keevo_core/widget/soft_icon_button.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../features/sync_indicator/presentation/widget/sync_indicator.dart';
import '../../../catalog/presentation/provider/category_provider.dart';
import '../../../catalog/presentation/widget/create_draft_product_bottom_sheet.dart';
import '../../../onboarding/domain/model/sector_type.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../domain/model/cart_item.dart';
import '../provider/cart_provider.dart';
import '../provider/pos_providers.dart';
import '../provider/pos_search_provider.dart';
import '../widget/cart_bottom_sheet.dart';
import '../widget/cart_pill.dart';
import '../widget/product_card.dart';

/// PosPage — main POS screen with product search, grid, and cart pill.
///
/// Replaces [PosPlaceholderPage] in Story 4.1.
class PosPage extends ConsumerStatefulWidget {
  const PosPage({super.key});

  @override
  ConsumerState<PosPage> createState() => _PosPageState();
}

class _PosPageState extends ConsumerState<PosPage> {
  final _searchController = TextEditingController();
  SectorType? _sectorType;
  String _searchQuery = '';
  String? _selectedCategoryId;
  late final ScrollController _scrollController;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController()..addListener(_onScroll);
    _loadSectorType();
  }

  Future<void> _loadSectorType() async {
    final prefs = await SharedPreferences.getInstance();
    final code = prefs.getString(kSectorTypeKey);
    if (code != null && mounted) {
      setState(() {
        _sectorType = SectorType.values.firstWhere(
          (s) => s.apiCode == code,
          orElse: () => SectorType.other,
        );
      });
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _searchController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_searchQuery.isNotEmpty) return;
    final pos = _scrollController.position;
    if (pos.pixels >= pos.maxScrollExtent - 300) {
      _triggerLoadMore();
    }
  }

  void _triggerLoadMore() {
    // null = "all stores": the grid is keyed on null too, so it paginates.
    final activeStoreId = ref.read(activeStoreIdProvider);
    final key = (storeId: activeStoreId, categoryId: _selectedCategoryId);
    ref.read(frequentProductsProvider(key).notifier).loadMore();
  }

  void _openCartBottomSheet() {
    CartBottomSheet.show(
      context,
      onEncaisser: () {
        Navigator.of(context).pop(); // close bottom sheet
        context.push<bool>('/pos/checkout').then((reopenCart) {
          if (mounted && reopenCart == true) {
            _openCartBottomSheet();
          }
        });
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    // Boutique active from Settings — null means "all stores"
    final storeId = ref.watch(activeStoreIdProvider);
    final role = ref.watch(currentUserRoleProvider);
    final isEmployee = role == 'EMPLOYEE';
    final cart = ref.watch(cartProvider);
    final cartNotifier = ref.read(cartProvider.notifier);
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Scaffold(
      backgroundColor: cs.surfaceContainerLowest,
      floatingActionButton: Padding(
        padding: const EdgeInsets.only(bottom: 56),
        // Couleur pleine + ombre douce plutôt que l'élévation Material par
        // défaut, comme le FAB du catalogue.
        child: Container(
          decoration: BoxDecoration(
            color: AppTheme.primary,
            borderRadius: BorderRadius.circular(AppTheme.radiusL),
            boxShadow: [
              BoxShadow(
                color: AppTheme.primary.withValues(alpha: 0.25),
                blurRadius: 12,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: FloatingActionButton(
            heroTag: 'pos_create_draft',
            backgroundColor: Colors.transparent,
            elevation: 0,
            onPressed: () => _createDraftAndAddToCart(context, ref, ''),
            child: const Icon(Icons.add_rounded, color: AppTheme.onBrand),
          ),
        ),
      ),
      body: Stack(
        children: [
          CustomScrollView(
            controller: _scrollController,
            slivers: [
              // ── Header épuré — fond neutre ──
              SliverAppBar(
                expandedHeight: 100,
                floating: true,
                snap: true,
                pinned: false,
                automaticallyImplyLeading: false,
                leading: context.canPop()
                    ? IconButton(
                        icon: Icon(Icons.arrow_back_ios_new_rounded,
                            color: cs.onSurface),
                        onPressed: () => context.pop(),
                      )
                    : null,
                elevation: 0,
                scrolledUnderElevation: 0,
                backgroundColor: cs.surface,
                flexibleSpace: FlexibleSpaceBar(
                  background: SafeArea(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(20, 8, 12, 12),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            'Caisse',
                            style: theme.textTheme.titleLarge?.copyWith(
                              color: cs.onSurface,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.3,
                            ),
                          ),
                          const Spacer(),
                          SoftIconButton(
                            icon: Icons.receipt_long_outlined,
                            tooltip: 'Mes Ventes',
                            onPressed: () => context.push('/pos/sales-history'),
                          ),
                          const SyncIndicator(),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              // ── Search bar on light background ──
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                  child: Container(
                    decoration: BoxDecoration(
                      color: cs.surface,
                      borderRadius: BorderRadius.circular(AppTheme.radiusL),
                      border: Border.all(color: cs.outlineVariant),
                    ),
                    child: TextField(
                      controller: _searchController,
                      decoration: InputDecoration(
                        hintText: 'Chercher un produit',
                        hintStyle: TextStyle(
                          color: cs.onSurfaceVariant.withValues(alpha: 0.7),
                          fontWeight: FontWeight.w400,
                        ),
                        prefixIcon: Icon(Icons.search_rounded,
                            color: cs.onSurfaceVariant),
                        suffixIcon: _searchQuery.isNotEmpty
                            ? IconButton(
                                icon: Icon(Icons.close_rounded,
                                    color: cs.onSurfaceVariant, size: 20),
                                onPressed: () {
                                  _searchController.clear();
                                  setState(() => _searchQuery = '');
                                  ref
                                      .read(posSearchProvider.notifier)
                                      .clear();
                                },
                              )
                            : null,
                        // Le conteneur porte la bordure : champ nu à l'intérieur.
                        border: InputBorder.none,
                        enabledBorder: InputBorder.none,
                        focusedBorder: InputBorder.none,
                        filled: false,
                        contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 14),
                      ),
                      onChanged: (value) {
                        setState(() => _searchQuery = value);
                        ref
                            .read(posSearchProvider.notifier)
                            .search(value, storeId);
                      },
                    ),
                  ),
                ),
              ),
              // Category filter chips
              if (_searchQuery.isEmpty)
                _CategoryChipsSliver(
                  selectedCategoryId: _selectedCategoryId,
                  onCategorySelected: (id) =>
                      setState(() => _selectedCategoryId = id),
                ),
              // Product grid or search results
              if (_searchQuery.isNotEmpty)
                _SearchResultsSliver(
                  onAddToCart: (p) => _addProductToCart(p, cartNotifier),
                  onCreateDraft: _createDraftAndAddToCart,
                ),
              if (_searchQuery.isEmpty)
                _FrequentProductsSliver(
                  storeId: storeId,
                  sectorType: _sectorType,
                  isEmployee: isEmployee,
                  categoryId: _selectedCategoryId,
                  onAddToCart: (p) => _addProductToCart(p, cartNotifier),
                ),
              // Loading footer when paginating the product grid
              if (_searchQuery.isEmpty)
                _PosGridLoadingFooter(
                  storeId: storeId,
                  categoryId: _selectedCategoryId,
                ),
              // Bottom padding: cart pill height + safe area + breathing room
              SliverPadding(
                padding: EdgeInsets.only(
                  bottom: MediaQuery.of(context).padding.bottom + 110,
                ),
              ),
            ],
          ),
          // Cart pill
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: CartPill(
              itemCount: cart.length,
              totalAmount: cartNotifier.totalAmount,
              onEncaisser: _openCartBottomSheet,
            ),
          ),
        ],
      ),
    );
  }

  void _addProductToCart(
      PosProductResult product, CartNotifier cartNotifier) {
    final item = CartItem(
      id: product.id,
      productId: product.id,
      productName: product.name,
      unitPrice: product.price,
      appliedUnitPrice: product.price,
      quantity: 1,
      availableStock: product.stock,
    );
    if (cartNotifier.addItem(item)) {
      HapticFeedback.lightImpact();
      return;
    }
    HapticFeedback.heavyImpact();
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(item.stockLimitMessage),
        backgroundColor: AppTheme.errorStrong,
        behavior: SnackBarBehavior.floating,
      ));
  }

  /// Story 4.3 — Create a draft product and add it to the cart.
  Future<void> _createDraftAndAddToCart(
      BuildContext ctx, WidgetRef widgetRef, String query) async {
    final result = await showModalBottomSheet<DraftCreationResult>(
      context: ctx,
      isScrollControlled: true,
      builder: (_) => CreateDraftProductBottomSheet(prefillName: query),
    );
    if (result != null && ctx.mounted) {
      final cartNotifier = widgetRef.read(cartProvider.notifier);
      HapticFeedback.lightImpact();
      cartNotifier.addItem(CartItem(
        id: result.product.id,
        productId: result.product.id,
        productName: result.product.name,
        unitPrice: result.product.price,
        appliedUnitPrice: result.product.price,
        quantity: result.quantity,
        productStatus: result.product.status,
      ));
    }
  }
}

/// Grid of frequently sold products — paginated, 24 items per page.
/// Falls back to all products if no sales exist yet.
class _FrequentProductsSliver extends ConsumerWidget {
  final String? storeId;
  final SectorType? sectorType;
  final bool isEmployee;
  final String? categoryId;
  final void Function(PosProductResult) onAddToCart;

  const _FrequentProductsSliver({
    required this.storeId,
    required this.sectorType,
    required this.isEmployee,
    this.categoryId,
    required this.onAddToCart,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = (storeId: storeId, categoryId: categoryId);
    final asyncState = ref.watch(frequentProductsProvider(key));
    return asyncState.when(
      loading: () => const SliverFillRemaining(
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => SliverFillRemaining(
        child: AppErrorWidget(
          error: e,
          onRetry: () => ref.invalidate(frequentProductsProvider(key)),
        ),
      ),
      data: (gridState) {
        final products = gridState.products;
        if (products.isEmpty) {
          return SliverFillRemaining(
            child: _EmptyState(
              sectorType: sectorType,
              isEmployee: isEmployee,
            ),
          );
        }
        return SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          sliver: SliverLayoutBuilder(
            builder: (context, constraints) {
              final crossAxisCount = constraints.crossAxisExtent < 600
                  ? 3
                  : constraints.crossAxisExtent < 1200
                      ? 4
                      : 5;
              return SliverGrid(
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    final p = products[index];
                    final card = ProductCard(
                      name: p.name,
                      price: p.price,
                      stockQuantity: p.stock,
                      photoUrl: p.photoUrl,
                      categoryName: p.categoryName,
                      onTap: () => onAddToCart(p),
                    );
                    if (!p.isDraft) return card;
                    return Stack(
                      children: [
                        card,
                        Positioned(
                          top: 6,
                          right: 6,
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: AppTheme.warning,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            // onWarning : le blanc est illisible sur l'ambre.
                            child: const Text(
                              'Brouillon',
                              style: TextStyle(
                                  color: AppTheme.onWarning,
                                  fontSize: 9,
                                  fontWeight: FontWeight.w700),
                            ),
                          ),
                        ),
                      ],
                    );
                  },
                  childCount: products.length,
                ),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: crossAxisCount,
                  childAspectRatio: 0.78,
                  crossAxisSpacing: 10,
                  mainAxisSpacing: 10,
                ),
              );
            },
          ),
        );
      },
    );
  }
}

/// Loading footer shown below the product grid while the next page is fetching.
class _PosGridLoadingFooter extends ConsumerWidget {
  final String? storeId;
  final String? categoryId;

  const _PosGridLoadingFooter({required this.storeId, required this.categoryId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = (storeId: storeId, categoryId: categoryId);
    final isLoading =
        ref.watch(frequentProductsProvider(key)).value?.isLoadingMore ??
            false;
    if (!isLoading) return const SliverToBoxAdapter(child: SizedBox.shrink());
    return const SliverToBoxAdapter(
      child: Padding(
        padding: EdgeInsets.symmetric(vertical: 16),
        child: Center(child: CircularProgressIndicator()),
      ),
    );
  }
}

/// Search results driven by PosSearchNotifier (AC2).
class _SearchResultsSliver extends ConsumerWidget {
  final void Function(PosProductResult) onAddToCart;
  final void Function(BuildContext, WidgetRef, String) onCreateDraft;

  const _SearchResultsSliver({
    required this.onAddToCart,
    required this.onCreateDraft,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchState = ref.watch(posSearchProvider);
    return searchState.when(
      loading: () => const SliverFillRemaining(
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => SliverFillRemaining(
        child: AppErrorWidget(
          error: e,
          // Re-run the failed query: invalidating would reset the notifier
          // to an empty result and lose the query.
          onRetry: () {
            final search = ref.read(posSearchProvider.notifier);
            search.search(search.lastQuery, ref.read(activeStoreIdProvider));
          },
        ),
      ),
      data: (results) {
        if (results.isEmpty) {
          final lastQuery = ref.read(posSearchProvider.notifier).lastQuery;
          return SliverFillRemaining(
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.search_off_rounded,
                      size: 48, color: Theme.of(context).colorScheme.outlineVariant),
                  const SizedBox(height: 12),
                  Text('Aucun produit trouvé',
                      style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    icon: const Icon(Icons.add_circle_outline),
                    label: Text("Créer '$lastQuery' à la volée"),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppTheme.warning,
                      foregroundColor: AppTheme.onWarning,
                    ),
                    onPressed: () => onCreateDraft(context, ref, lastQuery),
                  ),
                ],
              ),
            ),
          );
        }
        return SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          sliver: SliverLayoutBuilder(
            builder: (context, constraints) {
              final crossAxisCount = constraints.crossAxisExtent < 600
                  ? 3
                  : constraints.crossAxisExtent < 1200
                      ? 4
                      : 5;
              return SliverGrid(
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    final p = results[index];
                    final card = ProductCard(
                      name: p.name,
                      price: p.price,
                      stockQuantity: p.stock,
                      photoUrl: p.photoUrl,
                      categoryName: p.categoryName,
                      onTap: () => onAddToCart(p),
                    );
                    if (!p.isDraft) return card;
                    return Stack(
                      children: [
                        card,
                        Positioned(
                          top: 6,
                          right: 6,
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: AppTheme.warning,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            // onWarning : le blanc est illisible sur l'ambre.
                            child: const Text(
                              'Brouillon',
                              style: TextStyle(
                                  color: AppTheme.onWarning,
                                  fontSize: 9,
                                  fontWeight: FontWeight.w700),
                            ),
                          ),
                        ),
                      ],
                    );
                  },
                  childCount: results.length,
                ),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: crossAxisCount,
                  childAspectRatio: 0.78,
                  crossAxisSpacing: 10,
                  mainAxisSpacing: 10,
                ),
              );
            },
          ),
        );
      },
    );
  }
}

/// Empty state shown when no products exist.
class _EmptyState extends StatelessWidget {
  final SectorType? sectorType;
  final bool isEmployee;

  const _EmptyState({this.sectorType, required this.isEmployee});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final sector = sectorType;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // L'emoji du secteur est une donnée : on le garde. Sans secteur,
            // badge icône plutôt qu'un emoji décoratif.
            if (sector != null)
              Text(
                sector.emoji,
                style: const TextStyle(fontSize: 72),
              )
            else
              const SoftIconBadge(icon: Icons.storefront_outlined, size: 96),
            const SizedBox(height: 24),
            Text(
              'Votre boutique est prête !',
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w700,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              isEmployee
                  ? 'Aucun produit configuré. Contactez votre responsable.'
                  : 'Commencez par ajouter un produit',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
            if (!isEmployee) ...[
              const SizedBox(height: 32),
              FilledButton.icon(
                onPressed: () => GoRouter.of(context).go('/products'),
                icon: const Icon(Icons.add),
                label: const Text('Ajouter un produit'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Horizontal scrollable category filter chips (Tous / Boissons / Épicerie…).
class _CategoryChipsSliver extends ConsumerWidget {
  final String? selectedCategoryId;
  final ValueChanged<String?> onCategorySelected;

  const _CategoryChipsSliver({
    required this.selectedCategoryId,
    required this.onCategorySelected,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncCategories = ref.watch(categoriesProvider);
    return asyncCategories.when(
      loading: () => const SliverToBoxAdapter(child: SizedBox.shrink()),
      error: (_, __) => const SliverToBoxAdapter(child: SizedBox.shrink()),
      data: (categories) {
        if (categories.isEmpty) {
          return const SliverToBoxAdapter(child: SizedBox.shrink());
        }
        // Only show root categories (no parentId)
        final roots = categories
            .where((c) => c.parentId == null && c.isActive)
            .toList();
        if (roots.isEmpty) {
          return const SliverToBoxAdapter(child: SizedBox.shrink());
        }
        return SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: SizedBox(
              height: 44,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(vertical: 4),
              itemCount: roots.length + 1,
              separatorBuilder: (_, __) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                final isAll = index == 0;
                final cat = isAll ? null : roots[index - 1];
                final isSelected = isAll
                    ? selectedCategoryId == null
                    : selectedCategoryId == cat!.id;
                final theme = Theme.of(context);
                return GestureDetector(
                  onTap: () => onCategorySelected(isAll ? null : cat!.id),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 18, vertical: 8),
                    decoration: BoxDecoration(
                      color: isSelected
                          ? AppTheme.primary
                          : AppTheme.softTint(theme.brightness),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      isAll ? 'Tous' : cat!.name,
                      style: TextStyle(
                        color: isSelected
                            ? AppTheme.onBrand
                            : theme.colorScheme.primary,
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                      ),
                    ),
                  ),
                );
              },            // itemBuilder
            ),              // ListView.separated
          ),                // SizedBox
        ),                  // Padding
        );                  // SliverToBoxAdapter (closes return statement)
      },                    // data: callback
    );                      // asyncCategories.when()
  }
}

