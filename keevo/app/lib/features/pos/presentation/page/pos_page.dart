import 'package:drift/drift.dart' show QueryRow, Variable;
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

    return Scaffold(
      appBar: AppBar(
        title: const Text('Point de Vente'),
        actions: const [SyncIndicator()],
      ),
      body: Stack(
        children: [
          Column(
            children: [
              // Search bar
              Padding(
                padding: const EdgeInsets.all(12),
                child: SearchBar(
                  controller: _searchController,
                  hintText: 'Rechercher un produit…',
                  leading: const Icon(Icons.search),
                  trailing: [
                    if (_searchQuery.isNotEmpty)
                      IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _searchQuery = '');
                        },
                      ),
                  ],
                  onChanged: (value) => setState(() => _searchQuery = value),
                ),
              ),
              // Product grid or search results
              Expanded(
                child: _searchQuery.isNotEmpty
                    ? _SearchResults(
                        query: _searchQuery,
                        storeId: storeId,
                        onAddToCart: (p) => _addProductToCart(p, cartNotifier),
                      )
                    : _AllProductsGrid(
                        storeId: storeId,
                        sectorType: _sectorType,
                        isEmployee: isEmployee,
                        onAddToCart: (p) => _addProductToCart(p, cartNotifier),
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
      _ProductGridItem product, CartNotifier cartNotifier) {
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

/// Internal data class for product grid display.
class _ProductGridItem {
  final String id;
  final String name;
  final int price;
  final int stock;
  final String? photoUrl;

  const _ProductGridItem({
    required this.id,
    required this.name,
    required this.price,
    required this.stock,
    this.photoUrl,
  });
}

/// Grid of ALL products for the active store (or all stores).
class _AllProductsGrid extends ConsumerStatefulWidget {
  final String? storeId;
  final SectorType? sectorType;
  final bool isEmployee;
  final void Function(_ProductGridItem) onAddToCart;

  const _AllProductsGrid({
    required this.storeId,
    required this.sectorType,
    required this.isEmployee,
    required this.onAddToCart,
  });

  @override
  ConsumerState<_AllProductsGrid> createState() => _AllProductsGridState();
}

class _AllProductsGridState extends ConsumerState<_AllProductsGrid> {
  late Future<List<_ProductGridItem>> _productsFuture;

  @override
  void initState() {
    super.initState();
    _productsFuture = _loadAllProducts();
  }

  @override
  void didUpdateWidget(_AllProductsGrid old) {
    super.didUpdateWidget(old);
    if (old.storeId != widget.storeId) {
      _productsFuture = _loadAllProducts();
    }
  }

  Future<List<_ProductGridItem>> _loadAllProducts() async {
    final db = ref.read(appDatabaseProvider);
    final sid = widget.storeId;
    final List<QueryRow> rows;
    if (sid != null) {
      rows = await db.customSelect(
        'SELECT p.id, p.name, p.price, p.photo_url, '
        'COALESCE(d.quantity, 0) as stock '
        'FROM products p '
        'LEFT JOIN ('
        '  SELECT sl.product_id, sl.quantity '
        '  FROM stock_levels sl '
        '  WHERE sl.store_id = ? '
        '  GROUP BY sl.product_id '
        '  HAVING sl.updated_at = MAX(sl.updated_at)'
        ') d ON d.product_id = p.id '
        'ORDER BY (CASE WHEN COALESCE(d.quantity, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
        'LIMIT 100',
        variables: [Variable.withString(sid)],
      ).get();
    } else {
      rows = await db.customSelect(
        'SELECT p.id, p.name, p.price, p.photo_url, '
        'COALESCE(agg.total_stock, 0) as stock '
        'FROM products p '
        'LEFT JOIN ('
        '  SELECT d.product_id, SUM(d.quantity) as total_stock '
        '  FROM ('
        '    SELECT sl.product_id, sl.store_id, sl.quantity '
        '    FROM stock_levels sl '
        '    GROUP BY sl.product_id, sl.store_id '
        '    HAVING sl.updated_at = MAX(sl.updated_at)'
        '  ) d '
        '  GROUP BY d.product_id'
        ') agg ON agg.product_id = p.id '
        'ORDER BY (CASE WHEN COALESCE(agg.total_stock, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
        'LIMIT 100',
      ).get();
    }
    return rows
        .map((r) => _ProductGridItem(
              id: r.read<String>('id'),
              name: r.read<String>('name'),
              price: r.read<int>('price'),
              stock: r.read<int>('stock'),
              photoUrl: r.readNullable<String>('photo_url'),
            ))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<_ProductGridItem>>(
      future: _productsFuture,
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final products = snapshot.data!;
        if (products.isEmpty) {
          return _EmptyState(
            sectorType: widget.sectorType,
            isEmployee: widget.isEmployee,
          );
        }
        return LayoutBuilder(
          builder: (context, constraints) {
            final crossAxisCount = constraints.maxWidth < 600
                ? 2
                : constraints.maxWidth < 1200
                    ? 3
                    : 4;
            return GridView.builder(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 80),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: crossAxisCount,
                childAspectRatio: 0.85,
                crossAxisSpacing: 8,
                mainAxisSpacing: 8,
              ),
              itemCount: products.length,
              itemBuilder: (context, index) {
                final p = products[index];
                return ProductCard(
                  name: p.name,
                  price: p.price,
                  stockQuantity: p.stock,
                  photoUrl: p.photoUrl,
                  onTap: () => widget.onAddToCart(p),
                );
              },
            );
          },
        );
      },
    );
  }
}

/// Search results list.
class _SearchResults extends ConsumerWidget {
  final String query;
  final String? storeId;
  final void Function(_ProductGridItem) onAddToCart;

  const _SearchResults({
    required this.query,
    required this.storeId,
    required this.onAddToCart,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final db = ref.watch(appDatabaseProvider);

    return FutureBuilder<List<_ProductGridItem>>(
      future: _search(db, query, storeId),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final results = snapshot.data!;
        if (results.isEmpty) {
          return const Center(
            child: Text('Aucun produit trouvé'),
          );
        }
        return LayoutBuilder(
          builder: (context, constraints) {
            final crossAxisCount = constraints.maxWidth < 600
                ? 2
                : constraints.maxWidth < 1200
                    ? 3
                    : 4;
            return GridView.builder(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 80),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: crossAxisCount,
                childAspectRatio: 0.85,
                crossAxisSpacing: 8,
                mainAxisSpacing: 8,
              ),
              itemCount: results.length,
              itemBuilder: (context, index) {
                final p = results[index];
                return ProductCard(
                  name: p.name,
                  price: p.price,
                  stockQuantity: p.stock,
                  photoUrl: p.photoUrl,
                  onTap: () => onAddToCart(p),
                );
              },
            );
          },
        );
      },
    );
  }

  Future<List<_ProductGridItem>> _search(
      dynamic db, String query, String? storeId) async {
    final lowerQuery = '%${query.toLowerCase()}%';
    final List<QueryRow> rows;
    if (storeId != null) {
      rows = await db.customSelect(
        'SELECT p.id, p.name, p.price, p.photo_url, '
        'COALESCE(d.quantity, 0) as stock '
        'FROM products p '
        'LEFT JOIN ('
        '  SELECT sl.product_id, sl.quantity '
        '  FROM stock_levels sl '
        '  WHERE sl.store_id = ? '
        '  GROUP BY sl.product_id '
        '  HAVING sl.updated_at = MAX(sl.updated_at)'
        ') d ON d.product_id = p.id '
        'WHERE LOWER(p.name) LIKE ? '
        'ORDER BY (CASE WHEN COALESCE(d.quantity, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
        'LIMIT 50',
        variables: [
          Variable.withString(storeId),
          Variable.withString(lowerQuery),
        ],
      ).get();
    } else {
      rows = await db.customSelect(
        'SELECT p.id, p.name, p.price, p.photo_url, '
        'COALESCE(agg.total_stock, 0) as stock '
        'FROM products p '
        'LEFT JOIN ('
        '  SELECT d.product_id, SUM(d.quantity) as total_stock '
        '  FROM ('
        '    SELECT sl.product_id, sl.store_id, sl.quantity '
        '    FROM stock_levels sl '
        '    GROUP BY sl.product_id, sl.store_id '
        '    HAVING sl.updated_at = MAX(sl.updated_at)'
        '  ) d '
        '  GROUP BY d.product_id'
        ') agg ON agg.product_id = p.id '
        'WHERE LOWER(p.name) LIKE ? '
        'ORDER BY (CASE WHEN COALESCE(agg.total_stock, 0) > 0 THEN 0 ELSE 1 END) ASC, p.name ASC '
        'LIMIT 50',
        variables: [
          Variable.withString(lowerQuery),
        ],
      ).get();
    }

    return rows
        .map((r) => _ProductGridItem(
              id: r.read<String>('id'),
              name: r.read<String>('name'),
              price: r.read<int>('price'),
              stock: r.read<int>('stock'),
              photoUrl: r.readNullable<String>('photo_url'),
            ))
        .toList();
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
