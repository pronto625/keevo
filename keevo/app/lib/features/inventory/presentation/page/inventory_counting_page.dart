import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../data/datasource/remote_quick_add_datasource.dart';
import '../../domain/model/inventory_product_row_model.dart';
import '../provider/inventory_counting_provider.dart';
import '../widget/inventory_row.dart';
import '../widget/quick_add_product_sheet.dart';

/// InventoryCountingPage — guided counting form for a session.
///
/// Shows products in scope with progress bar, filter FAB, search,
/// and "Valider l'inventaire" button when 100% counted.
///
/// Story 6.2.
class InventoryCountingPage extends ConsumerStatefulWidget {
  final String sessionId;

  const InventoryCountingPage({super.key, required this.sessionId});

  @override
  ConsumerState<InventoryCountingPage> createState() =>
      _InventoryCountingPageState();
}

class _InventoryCountingPageState
    extends ConsumerState<InventoryCountingPage> {
  bool _isSearching = false;
  final _searchController = TextEditingController();
  final _scrollController = ScrollController();
  String? _scrollToProductId;

  @override
  void dispose() {
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final productsAsync =
        ref.watch(countingProductsProvider(widget.sessionId));
    final filter = ref.watch(inventoryCountFilterProvider);
    final search = ref.watch(inventoryCountSearchProvider);

    return Scaffold(
      appBar: AppBar(
        title: _isSearching
            ? TextField(
                controller: _searchController,
                autofocus: true,
                decoration: const InputDecoration(
                  hintText: 'Rechercher un produit…',
                  border: InputBorder.none,
                ),
                onChanged: (v) =>
                    ref.read(inventoryCountSearchProvider.notifier).state = v,
              )
            : const Text('Comptage'),
        actions: [
          IconButton(
            icon: Icon(_isSearching ? Icons.close : Icons.search),
            onPressed: () {
              setState(() {
                _isSearching = !_isSearching;
                if (!_isSearching) {
                  _searchController.clear();
                  ref.read(inventoryCountSearchProvider.notifier).state = '';
                }
              });
            },
          ),
        ],
      ),
      floatingActionButton: null,
      body: productsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text('Erreur : $e',
                textAlign: TextAlign.center,
                style: TextStyle(color: theme.colorScheme.error)),
          ),
        ),
        data: (products) {
          final filtered = _applyFilters(products, filter, search);
          final total = products.length;
          final counted = products.where((p) => p.isCounted).length;
          final progress = total > 0 ? counted / total : 0.0;
          final discrepancies =
              products.where((p) => p.ecart != null && p.ecart != 0).length;
          final matches =
              products.where((p) => p.ecart != null && p.ecart == 0).length;

          return LayoutBuilder(
            builder: (context, constraints) {
              final isExpanded = constraints.maxWidth > 840;

              final progressSection = _ProgressSection(
                progress: progress,
                counted: counted,
                total: total,
              );

              final summaryBanner = progress >= 1.0
                  ? _SummaryBanner(matches: matches, discrepancies: discrepancies)
                  : null;

              final bottomBar = _BottomActionBar(
                progress: progress,
                filter: filter,
                onFilter: () => _showFilterSheet(context, ref, products),
                onQuickAdd: () => _showQuickAddSheet(context, products),
                onValidate: progress >= 1.0
                    ? () => context.push(
                        '/inventory/gap-report/${widget.sessionId}')
                    : null,
              );

              // Scroll to newly added product after list rebuilds
              if (_scrollToProductId != null) {
                final targetId = _scrollToProductId;
                _scrollToProductId = null;
                WidgetsBinding.instance.addPostFrameCallback((_) {
                  final targetIndex = filtered.indexWhere(
                      (p) => p.productId == targetId);
                  if (targetIndex >= 0 && _scrollController.hasClients) {
                    _scrollController.animateTo(
                      targetIndex * 72.0, // approximate row height
                      duration: const Duration(milliseconds: 400),
                      curve: Curves.easeOutCubic,
                    );
                  }
                });
              }

              final productList = filtered.isEmpty
                  ? Center(
                      child: Text(
                        'Aucun produit trouvé',
                        style: theme.textTheme.bodyLarge?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    )
                  : ListView.builder(
                      controller: _scrollController,
                      itemCount: filtered.length,
                      itemBuilder: (context, index) {
                        return InventoryRow(
                          key: ValueKey(filtered[index].compositeKey),
                          product: filtered[index],
                          sessionId: widget.sessionId,
                        );
                      },
                    );

              if (isExpanded) {
                // 2-column layout (>840dp)
                return Row(
                  children: [
                    // Left panel: progress + filter info
                    SizedBox(
                      width: constraints.maxWidth * 0.25,
                      child: Column(
                        children: [
                          progressSection,
                          if (summaryBanner != null) summaryBanner,
                          const Spacer(),
                          bottomBar,
                        ],
                      ),
                    ),
                    const VerticalDivider(width: 1),
                    // Right panel: product list
                    Expanded(child: productList),
                  ],
                );
              }

              // Single-column layout (<= 840dp)
              return Column(
                children: [
                  progressSection,
                  if (summaryBanner != null) summaryBanner,
                  Expanded(child: productList),
                  bottomBar,
                ],
              );
            },
          );
        },
      ),
    );
  }

  void _showQuickAddSheet(
      BuildContext context, List<InventoryProductRowModel> products) async {
    final messenger = ScaffoldMessenger.of(context);

    final result = await showQuickAddProductSheet(
      context: context,
      sessionId: widget.sessionId,
    );

    if (result == null || !mounted) return;

    if (result == 'COUNT_EXISTING') {
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Recherchez le produit dans la liste pour le compter.'),
          behavior: SnackBarBehavior.floating,
        ),
      );
      return;
    }

    if (result is QuickAddResult) {
      // Schedule scroll-to after the list rebuilds with the new product
      _scrollToProductId = result.productId;
      // Invalidate the family provider WITH the session ID
      ref.invalidate(countingProductsProvider(widget.sessionId));
      messenger.showSnackBar(
        SnackBar(
          content: Text('Produit « ${result.productName} » ajouté et compté'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  void _showFilterSheet(
      BuildContext context, WidgetRef ref, List<InventoryProductRowModel> products) {
    final current = ref.read(inventoryCountFilterProvider);
    final uncountedCount = products.where((p) => !p.isCounted).length;
    final discrepancyCount =
        products.where((p) => p.ecart != null && p.ecart != 0).length;

    showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            RadioListTile<InventoryCountFilter>(
              value: InventoryCountFilter.all,
              groupValue: current,
              title: const Text('Tous'),
              subtitle: Text('${products.length} produits'),
              onChanged: (v) {
                ref.read(inventoryCountFilterProvider.notifier).state = v!;
                Navigator.pop(ctx);
              },
            ),
            RadioListTile<InventoryCountFilter>(
              value: InventoryCountFilter.uncounted,
              groupValue: current,
              title: const Text('Non comptés'),
              subtitle: Text('$uncountedCount restants'),
              onChanged: (v) {
                ref.read(inventoryCountFilterProvider.notifier).state = v!;
                Navigator.pop(ctx);
              },
            ),
            RadioListTile<InventoryCountFilter>(
              value: InventoryCountFilter.discrepancies,
              groupValue: current,
              title: const Text('Écarts seulement'),
              subtitle: Text('$discrepancyCount produits avec écart'),
              onChanged: (v) {
                ref.read(inventoryCountFilterProvider.notifier).state = v!;
                Navigator.pop(ctx);
              },
            ),
          ],
        ),
      ),
    );
  }

  List<InventoryProductRowModel> _applyFilters(
    List<InventoryProductRowModel> products,
    InventoryCountFilter filter,
    String search,
  ) {
    var result = products;

    // Apply filter (AC4: 3 options)
    switch (filter) {
      case InventoryCountFilter.uncounted:
        result = result.where((p) => !p.isCounted).toList();
      case InventoryCountFilter.discrepancies:
        result = result
            .where((p) => p.ecart != null && p.ecart != 0)
            .toList();
      case InventoryCountFilter.all:
        break;
    }

    // Apply search (AC5: name OR sku)
    if (search.isNotEmpty) {
      final lower = search.toLowerCase();
      result = result
          .where((p) =>
              p.productName.toLowerCase().contains(lower) ||
              (p.sku != null && p.sku!.toLowerCase().contains(lower)))
          .toList();
    }

    return result;
  }
}

// ── Progress section ────────────────────────────────────────────────────────

class _ProgressSection extends StatelessWidget {
  final double progress;
  final int counted;
  final int total;

  const _ProgressSection({
    required this.progress,
    required this.counted,
    required this.total,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '$counted / $total produits comptés',
                style: theme.textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              Text(
                '${(progress * 100).toStringAsFixed(0)}%',
                style: theme.textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: progress >= 1.0
                      ? const Color(0xFF51CF66)
                      : theme.colorScheme.primary,
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: progress,
              minHeight: 8,
              backgroundColor:
                  theme.colorScheme.surfaceContainerHighest,
              color: progress >= 1.0
                  ? const Color(0xFF51CF66)
                  : theme.colorScheme.primary,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Summary banner at 100% (AC3) ────────────────────────────────────────────

class _SummaryBanner extends StatelessWidget {
  final int matches;
  final int discrepancies;

  const _SummaryBanner({required this.matches, required this.discrepancies});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF51CF66).withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          const Icon(Icons.check_circle, color: Color(0xFF51CF66)),
          const SizedBox(width: 8),
          Text(
            'Inventaire terminé — $matches concordants, $discrepancies écarts',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
          ),
        ],
      ),
    );
  }
}

// ── Bottom action bar (filter + validate) ───────────────────────────────────

class _BottomActionBar extends StatelessWidget {
  final double progress;
  final InventoryCountFilter filter;
  final VoidCallback onFilter;
  final VoidCallback onQuickAdd;
  final VoidCallback? onValidate;

  const _BottomActionBar({
    required this.progress,
    required this.filter,
    required this.onFilter,
    required this.onQuickAdd,
    required this.onValidate,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isActive = filter != InventoryCountFilter.all;

    return Container(
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 8,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Row(
            children: [
              // Filter button
              OutlinedButton.icon(
                onPressed: onFilter,
                icon: Icon(
                  Icons.filter_list,
                  color: isActive
                      ? theme.colorScheme.onPrimary
                      : theme.colorScheme.primary,
                ),
                label: Text(
                  _filterLabel(filter),
                  style: TextStyle(
                    color: isActive
                        ? theme.colorScheme.onPrimary
                        : theme.colorScheme.primary,
                  ),
                ),
                style: OutlinedButton.styleFrom(
                  backgroundColor:
                      isActive ? theme.colorScheme.primary : null,
                  side: BorderSide(color: theme.colorScheme.primary),
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                ),
              ),
              const SizedBox(width: 8),
              // Quick-add button
              IconButton.filled(
                onPressed: onQuickAdd,
                icon: const Icon(Icons.add),
                tooltip: 'Ajouter un produit',
                style: IconButton.styleFrom(
                  backgroundColor: theme.colorScheme.secondaryContainer,
                  foregroundColor: theme.colorScheme.onSecondaryContainer,
                ),
              ),
              const SizedBox(width: 8),
              // Validate button (expanded)
              Expanded(
                child: FilledButton.icon(
                  onPressed: onValidate,
                  icon: const Icon(Icons.check_circle_outline, size: 20),
                  label: const Text("Valider l'inventaire"),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _filterLabel(InventoryCountFilter f) {
    switch (f) {
      case InventoryCountFilter.all:
        return 'Filtrer';
      case InventoryCountFilter.uncounted:
        return 'Non comptés';
      case InventoryCountFilter.discrepancies:
        return 'Écarts';
    }
  }
}
