import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../../../features/sync_indicator/presentation/widget/sync_indicator.dart';
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

  @override
  void initState() {
    super.initState();
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
      body: Stack(
        children: [
          CustomScrollView(
            slivers: [
              // Modern gradient AppBar with search
              SliverAppBar(
                floating: true,
                snap: true,
                expandedHeight: 120,
                backgroundColor: const Color(0xFF3B5BDB),
                flexibleSpace: FlexibleSpaceBar(
                  background: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        colors: [Color(0xFF3B5BDB), Color(0xFF4DABF7)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                    ),
                    child: SafeArea(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text(
                                  'Point de Vente',
                                  style: theme.textTheme.titleLarge?.copyWith(
                                    color: Colors.white,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                                const Spacer(),
                                const SyncIndicator(),
                              ],
                            ),
                            const SizedBox(height: 12),
                            // Search bar
                            Container(
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(14),
                                boxShadow: [
                                  BoxShadow(
                                    color: Colors.black.withValues(alpha: 0.08),
                                    blurRadius: 8,
                                    offset: const Offset(0, 2),
                                  ),
                                ],
                              ),
                              child: TextField(
                                controller: _searchController,
                                decoration: InputDecoration(
                                  hintText: 'Rechercher un produit…',
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
                                            ref.read(posSearchProvider.notifier).clear();
                                          },
                                        )
                                      : null,
                                  border: InputBorder.none,
                                  contentPadding: const EdgeInsets.symmetric(
                                      horizontal: 16, vertical: 14),
                                ),
                                onChanged: (value) {
                                  setState(() => _searchQuery = value);
                                  ref.read(posSearchProvider.notifier).search(value, storeId);
                                },
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              // Product grid or search results
              _searchQuery.isNotEmpty
                  ? _SearchResultsSliver(
                      onAddToCart: (p) => _addProductToCart(p, cartNotifier),
                    )
                  : _FrequentProductsSliver(
                      storeId: storeId,
                      sectorType: _sectorType,
                      isEmployee: isEmployee,
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
      PosProductResult product, CartNotifier cartNotifier) {
    HapticFeedback.lightImpact();
    cartNotifier.addItem(CartItem(
      id: product.id,
      productId: product.id,
      productName: product.name,
      unitPrice: product.price,
      appliedUnitPrice: product.price,
      quantity: 1,
    ));
  }
}

/// Grid of frequently sold products (AC1 — sale_items.quantity DESC, limit 12).
/// Falls back to all products if no sales exist yet.
class _FrequentProductsSliver extends ConsumerWidget {
  final String? storeId;
  final SectorType? sectorType;
  final bool isEmployee;
  final void Function(PosProductResult) onAddToCart;

  const _FrequentProductsSliver({
    required this.storeId,
    required this.sectorType,
    required this.isEmployee,
    required this.onAddToCart,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncProducts = ref.watch(frequentProductsProvider(storeId));
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
                      onTap: () => onAddToCart(p),
                    );
                  },
                  childCount: products.length,
                ),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: crossAxisCount,
                  childAspectRatio: 0.88,
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

/// Search results driven by PosSearchNotifier (AC2).
class _SearchResultsSliver extends ConsumerWidget {
  final void Function(PosProductResult) onAddToCart;

  const _SearchResultsSliver({required this.onAddToCart});

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
                      onTap: () => onAddToCart(p),
                    );
                  },
                  childCount: results.length,
                ),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: crossAxisCount,
                  childAspectRatio: 0.88,
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
