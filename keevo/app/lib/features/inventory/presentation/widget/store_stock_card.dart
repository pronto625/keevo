import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/di/providers.dart';
import '../../../catalog/presentation/widget/cross_store_availability_bottom_sheet.dart';
import '../../domain/model/store_product_stock_model.dart';
import '../../domain/model/store_stock_summary_model.dart';
import '../provider/global_stock_provider.dart';
import 'store_product_stock_tile.dart';

/// StoreStockCard — expandable card showing aggregated stock for one store.
/// Story 3.2.
class StoreStockCard extends ConsumerStatefulWidget {
  final StoreStockSummaryModel summary;

  const StoreStockCard({super.key, required this.summary});

  @override
  ConsumerState<StoreStockCard> createState() => _StoreStockCardState();
}

class _StoreStockCardState extends ConsumerState<StoreStockCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final s = widget.summary;
    final isWarehouse = s.storeType == 'WAREHOUSE';

    // AC3: auto-expand and scroll when this card is highlighted from search.
    final highlightedId = ref.watch(highlightedStoreIdProvider);
    if (highlightedId == s.storeId && !_expanded) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        setState(() => _expanded = true);
        Scrollable.ensureVisible(
          context,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
          alignment: 0.1,
        );
        ref.read(highlightedStoreIdProvider.notifier).state = null;
      });
    }

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Material(
        elevation: 0,
        borderRadius: BorderRadius.circular(20),
        child: InkWell(
          borderRadius: BorderRadius.circular(20),
          onTap: () => setState(() => _expanded = !_expanded),
          child: Container(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(20),
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [
                  theme.colorScheme.surface,
                  theme.colorScheme.surfaceContainerLow,
                ],
              ),
              border: Border.all(
                color: theme.colorScheme.outline.withValues(alpha: 0.1),
                width: 1,
              ),
              boxShadow: [
                BoxShadow(
                  color: theme.colorScheme.shadow.withValues(alpha: 0.06),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Column(
              children: [
                // ── Header ───────────────────────────────────────────
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    children: [
                      // Avatar
                      Container(
                        width: 48,
                        height: 48,
                        decoration: BoxDecoration(
                          color: isWarehouse
                              ? const Color(0xFFE8F4FD)
                              : const Color(0xFFD0EBFF),
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: Icon(
                          isWarehouse
                              ? Icons.warehouse_rounded
                              : Icons.storefront_rounded,
                          color: AppTheme.primary,
                          size: 24,
                        ),
                      ),
                      const SizedBox(width: 14),
                      // Name + type
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              s.storeName,
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.w600,
                                letterSpacing: -0.2,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            const SizedBox(height: 2),
                            Row(
                              children: [
                                _TypeChip(isWarehouse: isWarehouse),
                                const SizedBox(width: 8),
                                Flexible(
                                  child: Text(
                                    '${s.productCount} article${s.productCount > 1 ? 's' : ''}',
                                    style: theme.textTheme.bodySmall?.copyWith(
                                      color: theme.colorScheme.outline,
                                    ),
                                    overflow: TextOverflow.ellipsis,
                                    maxLines: 1,
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      // Right block
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          if (ref.watch(currentUserRoleProvider) == 'OWNER') ...[  
                            Text(
                              NumberFormat.currency(locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0).format(s.totalValueXaf),
                              style: theme.textTheme.titleSmall?.copyWith(
                                fontWeight: FontWeight.bold,
                                color: theme.colorScheme.primary,
                              ),
                            ),
                            const SizedBox(height: 4),
                          ],
                          Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if (s.lowStockCount > 0) ...[
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                      horizontal: 6, vertical: 2),
                                  decoration: BoxDecoration(
                                    color: AppTheme.warning.withValues(alpha: 0.15),
                                    borderRadius: BorderRadius.circular(8),
                                    border: Border.all(
                                        color: AppTheme.warning.withValues(alpha: 0.4)),
                                  ),
                                  child: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Icon(Icons.warning_amber_rounded,
                                          size: 12, color: AppTheme.warning),
                                      const SizedBox(width: 3),
                                      Text(
                                        '${s.lowStockCount}',
                                        style: theme.textTheme.labelSmall
                                            ?.copyWith(
                                                color: AppTheme.warning,
                                                fontWeight: FontWeight.bold),
                                      ),
                                    ],
                                  ),
                                ),
                                const SizedBox(width: 6),
                              ],
                              Icon(
                                _expanded
                                    ? Icons.keyboard_arrow_up_rounded
                                    : Icons.keyboard_arrow_down_rounded,
                                size: 20,
                                color: theme.colorScheme.outline,
                              ),
                            ],
                          ),
                        ],
                      ),
                    ],
                  ),
                ),

                // ── Expandable product list ──────────────────────────
                AnimatedCrossFade(
                  firstChild: const SizedBox.shrink(),
                  secondChild: _ProductList(storeId: s.storeId),
                  crossFadeState: _expanded
                      ? CrossFadeState.showSecond
                      : CrossFadeState.showFirst,
                  duration: const Duration(milliseconds: 200),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _TypeChip extends StatelessWidget {
  final bool isWarehouse;
  const _TypeChip({required this.isWarehouse});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: theme.colorScheme.secondaryContainer,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        isWarehouse ? 'Entrepôt' : 'Boutique',
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.onSecondaryContainer,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }
}

// ── Paginated product list (AC2 — "Voir plus") ────────────────────────────────

class _ProductList extends ConsumerStatefulWidget {
  final String storeId;
  const _ProductList({required this.storeId});

  @override
  ConsumerState<_ProductList> createState() => _ProductListState();
}

class _ProductListState extends ConsumerState<_ProductList> {
  final List<StoreProductStockModel> _items = [];
  int _page = 0;
  bool _hasMore = true;
  bool _isLoading = false;
  Object? _loadError;
  static const _kPageSize = 25;

  @override
  void initState() {
    super.initState();
    _loadPage();
  }

  Future<void> _loadPage() async {
    if (_isLoading || !_hasMore) return;
    setState(() {
      _isLoading = true;
      _loadError = null;
    });
    try {
      final lowOnly = ref.read(showLowStockOnlyProvider);
      final newItems = await ref
          .read(multiStoreStockRepositoryProvider)
          .getStoreStockDetail(widget.storeId,
              page: _page, size: _kPageSize, sortLowFirst: true, lowOnly: lowOnly);
      if (!mounted) return;
      setState(() {
        _items.addAll(newItems);
        _hasMore = newItems.length >= _kPageSize;
        _page++;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _loadError = e;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    Widget content;
    if (_items.isEmpty && _isLoading) {
      content = const Padding(
        padding: EdgeInsets.symmetric(vertical: 20),
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
    } else if (_items.isEmpty && _loadError != null) {
      content = Padding(
        padding: const EdgeInsets.all(16),
        child: Text('Erreur: $_loadError',
            style: TextStyle(color: theme.colorScheme.error)),
      );
    } else if (_items.isEmpty) {
      content = Padding(
        padding: const EdgeInsets.symmetric(vertical: 20),
        child: Text(
          'Aucun produit dans ce stock.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.outline),
        ),
      );
    } else {
      content = Column(
        children: [
          ..._items.map(
            (e) => StoreProductStockTile(
              entry: e,
              onTap: () => showCrossStoreAvailabilitySheet(
                context: context,
                productId: e.productId,
                productName: e.productName,
              ),
            ),
          ),
          if (_hasMore) ...[
            if (_isLoading)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 10),
                child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
              )
            else
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: TextButton.icon(
                  onPressed: _loadPage,
                  icon: const Icon(Icons.expand_more_rounded, size: 18),
                  label: const Text('Voir plus'),
                ),
              ),
          ],
          const SizedBox(height: 8),
        ],
      );
    }

    return Column(
      children: [
        Divider(
          height: 1,
          color: theme.colorScheme.outline.withValues(alpha: 0.1),
          indent: 16,
          endIndent: 16,
        ),
        content,
      ],
    );
  }
}

