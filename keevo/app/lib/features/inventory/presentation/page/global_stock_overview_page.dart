import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../provider/global_stock_provider.dart';
import '../widget/store_product_stock_tile.dart';
import '../widget/store_stock_card.dart';

/// GlobalStockOverviewPage — centralized multi-store stock view.
/// Route: /stock/overview. Story 3.2.
class GlobalStockOverviewPage extends ConsumerStatefulWidget {
  final bool showLowOnly;
  const GlobalStockOverviewPage({super.key, this.showLowOnly = false});

  @override
  ConsumerState<GlobalStockOverviewPage> createState() =>
      _GlobalStockOverviewPageState();
}

class _GlobalStockOverviewPageState
    extends ConsumerState<GlobalStockOverviewPage> {
  final TextEditingController _searchCtrl = TextEditingController();
  Timer? _debounce;
  bool _isRefreshing = false;

  @override
  void initState() {
    super.initState();
    _searchCtrl.addListener(_onSearchChanged);
    // Set low-stock filter when navigating from the dashboard badge.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        ref.read(showLowStockOnlyProvider.notifier).state = widget.showLowOnly;
      }
    });
  }

  void _onSearchChanged() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      ref.read(stockSearchQueryProvider.notifier).state = _searchCtrl.text;
    });
  }

  Future<void> _refresh() async {
    setState(() => _isRefreshing = true);
    ref.invalidate(globalStockOverviewProvider);
    await Future.delayed(const Duration(milliseconds: 400));
    if (mounted) setState(() => _isRefreshing = false);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchCtrl.removeListener(_onSearchChanged);
    _searchCtrl.dispose();
    ref.read(showLowStockOnlyProvider.notifier).state = false;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    // AC4: invalidate overview when device comes back online.
    ref.listen<AsyncValue<SyncStatus>>(syncStatusProvider, (prev, next) {
      if (next.valueOrNull == SyncStatus.online &&
          prev?.valueOrNull != SyncStatus.online) {
        ref.invalidate(globalStockOverviewProvider);
      }
    });

    final query = ref.watch(stockSearchQueryProvider);
    final isSearching = query.trim().length >= 2;

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // ── App bar — même style que CatalogPage ───────────────────────
          SliverAppBar(
            expandedHeight: 160,
            floating: false,
            pinned: true,
            elevation: 0,
            backgroundColor: Colors.transparent,
            actions: [
              // Story 3.3 — navigate to transfer history (OWNER only)
              if (ref.watch(currentUserRoleProvider) != 'EMPLOYEE')
                IconButton(
                  icon: const Icon(Icons.swap_horiz_rounded, color: Colors.white),
                  onPressed: () => context.push('/stock/transfers'),
                  tooltip: 'Historique des transferts',
                ),
              IconButton(
                icon: _isRefreshing
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: Colors.white))
                    : const Icon(Icons.refresh_rounded, color: Colors.white),
                onPressed: _isRefreshing ? null : _refresh,
                tooltip: 'Actualiser',
              ),
            ],
            flexibleSpace: FlexibleSpaceBar(
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      theme.colorScheme.primary,
                      theme.colorScheme.primary.withValues(alpha: 0.8),
                    ],
                  ),
                ),
                child: SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(24, 16, 24, 20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        Text(
                          '🏭 Vue Stock',
                          style: theme.textTheme.headlineMedium?.copyWith(
                            color: Colors.white,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Stock centralisé de toutes vos boutiques',
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: Colors.white.withValues(alpha: 0.9),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),

          // ── Search bar ─────────────────────────────────────────────────
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Material(
                elevation: 0,
                borderRadius: BorderRadius.circular(16),
                child: TextField(
                  controller: _searchCtrl,
                  decoration: InputDecoration(
                    hintText: 'Rechercher un produit…',
                    prefixIcon: Icon(Icons.search_rounded,
                        color: theme.colorScheme.outline),
                    suffixIcon: query.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear_rounded),
                            onPressed: () {
                              _searchCtrl.clear();
                              ref
                                  .read(stockSearchQueryProvider.notifier)
                                  .state = '';
                            },
                          )
                        : null,
                    filled: true,
                    fillColor: theme.colorScheme.surfaceContainerLow,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(16),
                      borderSide: BorderSide.none,
                    ),
                    contentPadding:
                        const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
                  ),
                ),
              ),
            ),
          ),

          // ── Content ────────────────────────────────────────────────────
          if (isSearching)
            _SearchResultsSliver(clearSearch: _searchCtrl.clear)
          else
            _OverviewSliver(),

          const SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }
}

// ── Search results ────────────────────────────────────────────────────────────

class _SearchResultsSliver extends ConsumerWidget {
  /// Clears the search text field in parent state (AC3: tap → expand card).
  final VoidCallback clearSearch;

  const _SearchResultsSliver({required this.clearSearch});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(stockSearchResultsProvider);
    return async.when(
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
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.search_off_rounded,
                      size: 56, color: theme.colorScheme.outline),
                  const SizedBox(height: 12),
                  Text('Aucun résultat trouvé.',
                      style: theme.textTheme.bodyLarge?.copyWith(
                          color: theme.colorScheme.outline)),
                ],
              ),
            ),
          );
        }
        return SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          sliver: SliverList(
            delegate: SliverChildBuilderDelegate(
              (_, i) {
                final result = results[i];
                return StoreProductStockTile(
                  entry: result,
                  // AC3: tap → highlight matching StoreStockCard and clear search
                  onTap: () {
                    ref
                        .read(highlightedStoreIdProvider.notifier)
                        .state = result.storeId;
                    ref.read(stockSearchQueryProvider.notifier).state = '';
                    clearSearch();
                  },
                );
              },
              childCount: results.length,
            ),
          ),
        );
      },
    );
  }
}

// ── Store overview list ───────────────────────────────────────────────────────

class _OverviewSliver extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(globalStockOverviewProvider);
    return async.when(
      loading: () => const SliverFillRemaining(
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => SliverFillRemaining(
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.cloud_off_rounded,
                  size: 56, color: theme.colorScheme.outline),
              const SizedBox(height: 12),
              Text('Données indisponibles',
                  style: theme.textTheme.titleMedium),
              const SizedBox(height: 4),
              Text('$e',
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.outline),
                  textAlign: TextAlign.center),
              const SizedBox(height: 16),
              FilledButton.tonal(
                onPressed: () => ref.invalidate(globalStockOverviewProvider),
                child: const Text('Réessayer'),
              ),
            ],
          ),
        ),
      ),
      data: (stores) {
        if (stores.isEmpty) {
          return SliverFillRemaining(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('🏪', style: TextStyle(fontSize: 48)),
                  const SizedBox(height: 12),
                  Text('Aucune boutique configurée.',
                      style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Text(
                    'Créez une boutique depuis Paramètres.',
                    style: theme.textTheme.bodyMedium
                        ?.copyWith(color: theme.colorScheme.outline),
                  ),
                ],
              ),
            ),
          );
        }

        // Global overview — all stores are visible regardless of role.
        // activeStoreIdProvider is POS context only; must not filter this view.
        final role = ref.watch(currentUserRoleProvider);
        final visibleStores = stores;
        final totalValue =
            visibleStores.fold<int>(0, (sum, s) => sum + s.totalValueXaf);

        if (visibleStores.isEmpty) {
          return SliverFillRemaining(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('🏷️', style: TextStyle(fontSize: 48)),
                  const SizedBox(height: 12),
                  Text('Aucun produit pour cette boutique.',
                      style: theme.textTheme.titleMedium),
                ],
              ),
            ),
          );
        }

        return SliverMainAxisGroup(slivers: [
          // AC5 — responsive grid: 1 col (<600px), 2 col (600–840px), 3 col (≥840px)
          SliverLayoutBuilder(
            builder: (_, constraints) {
              final w = constraints.crossAxisExtent;
              final cols = w >= 840 ? 3 : w >= 600 ? 2 : 1;
              if (cols == 1) {
                return SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (_, i) => StoreStockCard(summary: visibleStores[i]),
                      childCount: visibleStores.length,
                    ),
                  ),
                );
              }
              return SliverPadding(
                padding: const EdgeInsets.all(12),
                sliver: SliverGrid(
                  gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: cols,
                    crossAxisSpacing: 12,
                    mainAxisSpacing: 12,
                    childAspectRatio: 1.5,
                  ),
                  delegate: SliverChildBuilderDelegate(
                    (_, i) => StoreStockCard(summary: visibleStores[i]),
                    childCount: visibleStores.length,
                  ),
                ),
              );
            },
          ),

          // Footer: valeur totale (OWNER only)
          if (role == 'OWNER')
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 20),
                decoration: BoxDecoration(
                  color: theme.colorScheme.primaryContainer,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('Valeur totale',
                        style: theme.textTheme.labelLarge?.copyWith(
                            color: theme.colorScheme.onPrimaryContainer)),
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        '${NumberFormat.compact(locale: 'fr_FR').format(totalValue)} XAF',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                          color: theme.colorScheme.onPrimaryContainer,
                        ),
                        textAlign: TextAlign.end,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ]);
      },
    );
  }
}
