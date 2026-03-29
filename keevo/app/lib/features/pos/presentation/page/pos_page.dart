import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../core/theme/app_theme.dart';
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

  @override
  void initState() {
    super.initState();
    _loadSectorType();
    _checkAutoClosureNotification();
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

  /// AC6 — Show auto-closure notification snackbar once after scheduler closes day
  Future<void> _checkAutoClosureNotification() async {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;

      final prefs = await SharedPreferences.getInstance();
      final today = DateTime.now();
      final todayStr = '${today.year}-${today.month.toString().padLeft(2, '0')}-${today.day.toString().padLeft(2, '0')}';
      final notifiedDate = prefs.getString(kAutoClosureNotifiedDate);

      // Check if we need to show notification (not shown today yet)
      if (notifiedDate != todayStr) {
        // Check for auto-closure message from provider
        // For now, we'll use a simple approach: if day was closed automatically yesterday
        // and user opens app today, show the notification
        final lastClosureDate = prefs.getString(kLastClosureDate);
        
        if (lastClosureDate != null && lastClosureDate != todayStr && mounted) {
          final yesterday = today.subtract(const Duration(days: 1));
          final yesterdayStr = '${yesterday.year}-${yesterday.month.toString().padLeft(2, '0')}-${yesterday.day.toString().padLeft(2, '0')}';
          
          if (lastClosureDate == yesterdayStr) {
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text('Votre journée du $lastClosureDate a été clôturée automatiquement.'),
                backgroundColor: Colors.orange,
                duration: const Duration(seconds: 5),
              ),
            );
            await prefs.setString(kAutoClosureNotifiedDate, todayStr);
          }
        }
      }
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
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

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      floatingActionButton: Padding(
        padding: const EdgeInsets.only(bottom: 56),
        child: FloatingActionButton(
          heroTag: 'pos_create_draft',
          backgroundColor: AppTheme.primary,
          onPressed: () => _createDraftAndAddToCart(context, ref, ''),
          child: const Icon(Icons.add_rounded, color: Colors.white),
        ),
      ),
      body: Stack(
        children: [
          CustomScrollView(
            slivers: [
              // ── Blue gradient header (consistent with other screens) ──
              SliverAppBar(
                expandedHeight: 100,
                floating: true,
                snap: true,
                pinned: false,
                automaticallyImplyLeading: false,
                elevation: 0,
                backgroundColor: AppTheme.primary,
                flexibleSpace: FlexibleSpaceBar(
                  background: Container(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: [AppTheme.primary, AppTheme.primary.withAlpha(180)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                    ),
                    child: SafeArea(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(20, 8, 12, 12),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text(
                              'Caisse',
                              style: theme.textTheme.titleLarge?.copyWith(
                                color: Colors.white,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            const Spacer(),
                            IconButton(
                              icon: const Icon(Icons.receipt_long,
                                  color: Colors.white70),
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
              ),
              // ── Search bar on light background ──
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                  child: Container(
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: Colors.grey.shade200),
                    ),
                    child: TextField(
                      controller: _searchController,
                      decoration: InputDecoration(
                        hintText: 'Chercher un produit',
                        hintStyle: TextStyle(
                          color: Colors.grey.shade400,
                          fontWeight: FontWeight.w400,
                        ),
                        prefixIcon: Icon(Icons.search_rounded,
                            color: Colors.grey.shade400),
                        suffixIcon: _searchQuery.isNotEmpty
                            ? IconButton(
                                icon: Icon(Icons.close_rounded,
                                    color: Colors.grey.shade500, size: 20),
                                onPressed: () {
                                  _searchController.clear();
                                  setState(() => _searchQuery = '');
                                  ref
                                      .read(posSearchProvider.notifier)
                                      .clear();
                                },
                              )
                            : null,
                        border: InputBorder.none,
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
              // Pending sales banner (OWNER only)
              if (!isEmployee) _PendingSalesBanner(storeId: storeId),
              // Product grid or search results
              _searchQuery.isNotEmpty
                  ? _SearchResultsSliver(
                      onAddToCart: (p) => _addProductToCart(p, cartNotifier),
                      onCreateDraft: _createDraftAndAddToCart,
                    )
                  : _FrequentProductsSliver(
                      storeId: storeId,
                      sectorType: _sectorType,
                      isEmployee: isEmployee,
                      categoryId: _selectedCategoryId,
                      onAddToCart: (p) => _addProductToCart(p, cartNotifier),
                    ),
              // Bottom padding for cart pill
              const SliverPadding(padding: EdgeInsets.only(bottom: 80)),
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
              hasDraftProducts: cartNotifier.hasDraftProducts,
              onEncaisser: () {
                CartBottomSheet.show(
                  context,
                  onEncaisser: () {
                    Navigator.of(context).pop(); // close bottom sheet
                    context.go('/pos/checkout');
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  void _addProductToCart(
      PosProductResult product, CartNotifier cartNotifier,
      {String productStatus = 'ACTIVE'}) {
    HapticFeedback.lightImpact();
    cartNotifier.addItem(CartItem(
      id: product.id,
      productId: product.id,
      productName: product.name,
      unitPrice: product.price,
      appliedUnitPrice: product.price,
      quantity: 1,
      productStatus: productStatus,
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
        productStatus: result.product.status.name.toUpperCase(),
      ));
    }
  }
}

/// Grid of frequently sold products (AC1 — sale_items.quantity DESC, limit 12).
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
    final asyncProducts = ref.watch(frequentProductsProvider(key));
    return asyncProducts.when(
      loading: () => const SliverFillRemaining(
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => SliverFillRemaining(
        child: Center(child: Text('Erreur: $e')),
      ),
      data: (products) {
        if (products.isEmpty) {
          return SliverFillRemaining(
            child: _EmptyState(
              sectorType: sectorType,
              isEmployee: isEmployee,
            ),
          );
        }
        return SliverPadding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
          sliver: SliverLayoutBuilder(
            builder: (context, constraints) {
              final crossAxisCount = constraints.crossAxisExtent < 600
                  ? 2
                  : constraints.crossAxisExtent < 1200
                      ? 3
                      : 4;
              return SliverGrid(
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    final p = products[index];
                    return ProductCard(
                      name: p.name,
                      price: p.price,
                      stockQuantity: p.stock,
                      photoUrl: p.photoUrl,
                      categoryName: p.categoryName,
                      onTap: () => onAddToCart(p),
                    );
                  },
                  childCount: products.length,
                ),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: crossAxisCount,
                  childAspectRatio: 0.75,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                ),
              );
            },
          ),
        );
      },
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
        child: Center(child: Text('Erreur: $e')),
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
                      size: 48, color: Colors.grey.shade300),
                  const SizedBox(height: 12),
                  Text('Aucun produit trouvé',
                      style: TextStyle(color: Colors.grey.shade500)),
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    icon: const Icon(Icons.add_circle_outline),
                    label: Text("Créer '$lastQuery' à la volée"),
                    style: FilledButton.styleFrom(
                      backgroundColor: Colors.amber.shade700,
                    ),
                    onPressed: () => onCreateDraft(context, ref, lastQuery),
                  ),
                ],
              ),
            ),
          );
        }
        return SliverPadding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
          sliver: SliverLayoutBuilder(
            builder: (context, constraints) {
              final crossAxisCount = constraints.crossAxisExtent < 600
                  ? 2
                  : constraints.crossAxisExtent < 1200
                      ? 3
                      : 4;
              return SliverGrid(
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    final p = results[index];
                    return ProductCard(
                      name: p.name,
                      price: p.price,
                      stockQuantity: p.stock,
                      photoUrl: p.photoUrl,
                      categoryName: p.categoryName,
                      onTap: () => onAddToCart(p),
                    );
                  },
                  childCount: results.length,
                ),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: crossAxisCount,
                  childAspectRatio: 0.75,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
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
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              sectorType?.emoji ?? '📊',
              style: const TextStyle(fontSize: 72),
            ),
            const SizedBox(height: 24),
            Text(
              'Votre boutique est prête !',
              style: Theme.of(context).textTheme.titleLarge,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              isEmployee
                  ? 'Aucun produit configuré. Contactez votre responsable.'
                  : 'Commencez par ajouter un produit',
              style: Theme.of(context).textTheme.bodyMedium,
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
          child: SizedBox(
            height: 52,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
              itemCount: roots.length + 1,
              separatorBuilder: (_, __) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                final isAll = index == 0;
                final cat = isAll ? null : roots[index - 1];
                final isSelected = isAll
                    ? selectedCategoryId == null
                    : selectedCategoryId == cat!.id;
                return GestureDetector(
                  onTap: () => onCategorySelected(isAll ? null : cat!.id),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 18, vertical: 8),
                    decoration: BoxDecoration(
                      color: isSelected
                          ? AppTheme.primary
                          : AppTheme.primary.withAlpha(20),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      isAll ? 'Tous' : cat!.name,
                      style: TextStyle(
                        color: isSelected
                            ? Colors.white
                            : AppTheme.primary,
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        );
      },
    );
  }
}

/// Tappable banner that appears when there are pending-validation sales.
class _PendingSalesBanner extends ConsumerWidget {
  final String? storeId;
  const _PendingSalesBanner({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count =
        ref.watch(pendingSalesCountProvider(storeId)).valueOrNull ?? 0;
    if (count == 0) return const SliverToBoxAdapter(child: SizedBox.shrink());

    return SliverToBoxAdapter(
      child: GestureDetector(
        onTap: () => context.push('/pos/pending'),
        child: Container(
          margin: const EdgeInsets.fromLTRB(12, 10, 12, 0),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: Colors.amber.shade50,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: Colors.amber.shade300),
          ),
          child: Row(
            children: [
              Icon(Icons.pending_actions_rounded,
                  color: Colors.amber.shade800, size: 22),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  '$count vente${count > 1 ? 's' : ''} en attente de validation',
                  style: TextStyle(
                    color: Colors.amber.shade900,
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                ),
              ),
              Icon(Icons.chevron_right_rounded,
                  color: Colors.amber.shade700, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}
