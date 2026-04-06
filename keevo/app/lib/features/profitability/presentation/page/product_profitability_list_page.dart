import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../../../../core/theme/app_theme.dart';
import '../../domain/model/product_profitability_model.dart';
import '../provider/profitability_providers.dart';
import '../widget/period_filter_chips.dart';
import '../widget/profitability_row.dart';
import '../widget/sort_chips.dart';

/// ProductProfitabilityListPage — AC1, AC2, AC3 of Story 7.4.
///
/// Displays profitability per product for a date range, with sort chips.
/// Route guard for EMPLOYEE is handled at router level (app_router.dart),
/// not in this widget.
class ProductProfitabilityListPage extends ConsumerWidget {
  final String? storeId;
  const ProductProfitabilityListPage({super.key, this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // AC10: Auto-refresh when device comes back online.
    ref.listen<AsyncValue<SyncStatus>>(syncStatusProvider, (prev, next) {
      if (next.valueOrNull == SyncStatus.online &&
          prev?.valueOrNull != SyncStatus.online) {
        ref.invalidate(productProfitabilityProvider);
      }
    });

    final period = ref.watch(selectedProfitabilityPeriodProvider);
    final sort = ref.watch(selectedSortProvider);
    final syncStatus = ref.watch(syncStatusProvider);
    final isOffline = switch (syncStatus.valueOrNull) {
      SyncStatus.offlineOk || SyncStatus.offlineCritical => true,
      _ => false,
    };

    final params = ProfitabilityParams(
      from: period.from,
      to: period.to,
      sort: sort,
      storeId: storeId,
    );

    final dataAsync = ref.watch(productProfitabilityProvider(params));

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      body: CustomScrollView(
        slivers: [
          // ── Header ────────────────────────────────────────────────────────
          SliverAppBar(
            pinned: true,
            backgroundColor: AppTheme.primary,
            title: const Text(
              'Rentabilité Produits',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
            ),
            iconTheme: const IconThemeData(color: Colors.white),
          ),

          // ── Filters ───────────────────────────────────────────────────────
          SliverToBoxAdapter(
            child: ColoredBox(
              color: Colors.white,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    PeriodFilterChips(
                      selectedFrom: period.from,
                      selectedTo: period.to,
                      onPeriodChanged: (from, to) => ref
                          .read(selectedProfitabilityPeriodProvider.notifier)
                          .state = (from: from, to: to),
                    ),
                    const SizedBox(height: 4),
                    SortChips(
                      selected: sort,
                      onChanged: (option) =>
                          ref.read(selectedSortProvider.notifier).state =
                              option,
                    ),
                  ],
                ),
              ),
            ),
          ),

          // ── Offline banner (AC10) ─────────────────────────────────────────
          if (isOffline)
            const SliverToBoxAdapter(child: _OfflineBanner()),

          // ── Data ──────────────────────────────────────────────────────────
          dataAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.cloud_off_outlined,
                        size: 48, color: Colors.black26),
                    const SizedBox(height: 12),
                    const Text(
                      'Impossible de charger les données',
                      style: TextStyle(color: Colors.black54),
                    ),
                    const SizedBox(height: 8),
                    FilledButton.tonal(
                      onPressed: () =>
                          ref.invalidate(productProfitabilityProvider),
                      child: const Text('Réessayer'),
                    ),
                  ],
                ),
              ),
            ),
            data: (entries) {
              if (entries.isEmpty) {
                return const SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.bar_chart_outlined,
                            size: 48, color: Colors.black26),
                        SizedBox(height: 12),
                        Text(
                          'Aucune vente sur cette période',
                          style: TextStyle(color: Colors.black45),
                        ),
                      ],
                    ),
                  ),
                );
              }

              return SliverList.separated(
                itemCount: entries.length,
                itemBuilder: (ctx, i) {
                  final entry = entries[i];
                  return ProductProfitabilityRow(
                    entry: entry,
                    onTap: () =>
                        context.push('/reports/rentabilite/${entry.productId}',
                            extra: entry),
                  );
                },
                separatorBuilder: (_, __) => const Divider(height: 1),
              );
            },
          ),
        ],
      ),
    );
  }
}

// ── Offline banner ───────────────────────────────────────────────────────────

class _OfflineBanner extends StatelessWidget {
  const _OfflineBanner();

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
      color: Colors.amber.shade50,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            Icon(Icons.cloud_off_outlined,
                size: 16, color: Colors.orange.shade700),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Mode hors ligne — données du dernier sync affiché',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Colors.orange.shade900,
                    ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── (no SliverPersistentHeaderDelegate needed — filters use SliverToBoxAdapter)
