import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
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

    final cs = theme.colorScheme;

    // Surface pleine + ombre douce partagée plutôt qu'un dégradé de fond.
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      decoration: AppTheme.softCardDecoration(cs),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: InkWell(
          borderRadius: BorderRadius.circular(AppTheme.radiusL),
          onTap: () => setState(() => _expanded = !_expanded),
          child: Column(
            children: [
              // ── Header ───────────────────────────────────────────
              Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    // Avatar
                    SoftIconBadge(
                      icon: isWarehouse
                          ? Icons.warehouse_rounded
                          : Icons.storefront_rounded,
                      size: 48,
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
                                    color: cs.onSurfaceVariant,
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
                                  color: AppTheme.warning.withValues(alpha: 0.12),
                                  borderRadius: BorderRadius.circular(20),
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
                // Built only when open: a collapsed card must not load (or
                // reload on each filter toggle) its product page.
                secondChild: _expanded
                    ? _ProductList(storeId: s.storeId)
                    : const SizedBox.shrink(),
                crossFadeState: _expanded
                    ? CrossFadeState.showSecond
                    : CrossFadeState.showFirst,
                duration: const Duration(milliseconds: 200),
              ),
            ],
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
        color: AppTheme.softTint(theme.brightness),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        isWarehouse ? 'Entrepôt' : 'Boutique',
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.primary,
          fontWeight: FontWeight.w600,
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
  // Bumped on reload so a page still in flight for the old filter is dropped.
  int _generation = 0;
  static const _kPageSize = 25;

  @override
  void initState() {
    super.initState();
    _loadPage();
  }

  Future<void> _loadPage() async {
    if (_isLoading || !_hasMore) return;
    final generation = _generation;
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
      if (!mounted || generation != _generation) return;
      setState(() {
        _items.addAll(newItems);
        _hasMore = newItems.length >= _kPageSize;
        _page++;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _isLoading = false;
        _loadError = e;
      });
    }
  }

  /// Low-stock filter changed: restart from the first page.
  void _reload() {
    setState(() {
      _generation++;
      _items.clear();
      _page = 0;
      _hasMore = true;
      _isLoading = false;
      _loadError = null;
    });
    _loadPage();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    ref.listen<bool>(showLowStockOnlyProvider, (prev, next) {
      if (prev != next) _reload();
    });

    Widget content;
    if (_items.isEmpty && _isLoading) {
      content = const Padding(
        padding: EdgeInsets.symmetric(vertical: 20),
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
    } else if (_items.isEmpty && _loadError != null) {
      content = Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            AppErrorInline(error: _loadError!),
            TextButton(onPressed: _loadPage, child: const Text('Réessayer')),
          ],
        ),
      );
    } else if (_items.isEmpty) {
      content = Padding(
        padding: const EdgeInsets.symmetric(vertical: 20),
        child: Text(
          'Aucun produit dans ce stock.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
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
          if (_loadError != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Text(
                appErrorMessage(_loadError!),
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.error),
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
          color: theme.colorScheme.outlineVariant,
          indent: 16,
          endIndent: 16,
        ),
        content,
      ],
    );
  }
}

