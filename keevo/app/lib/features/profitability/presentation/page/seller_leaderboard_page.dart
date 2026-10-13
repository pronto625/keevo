import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/sync/sync_status.dart';
import '../../../../core/sync/sync_status_provider.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../provider/profitability_providers.dart';
import '../widget/offline_data_banner.dart';
import '../widget/period_filter_chips.dart';
import '../widget/ranking_metric_toggle.dart';
import '../widget/seller_leaderboard_row.dart';

/// SellerLeaderboardPage — « Équipe » tab of the OWNER reports hub (FR44,
/// v1s-14-2). Ranks the sellers of [storeId] (null = all stores) by CA,
/// number of sales or average basket over the selected period (default
/// today). Never shown to employees (the tab only exists in OwnerReportsPage
/// and the backend answers 403).
class SellerLeaderboardPage extends ConsumerWidget {
  final String? storeId;

  const SellerLeaderboardPage({super.key, this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Refresh when the device comes back online (backend becomes the
    // source). Only a real offline → online transition re-fetches: the first
    // status emission (loading → online) must not double the initial fetch.
    ref.listen<AsyncValue<SyncStatus>>(syncStatusProvider, (prev, next) {
      final wasOffline = switch (prev?.valueOrNull) {
        SyncStatus.offlineOk || SyncStatus.offlineCritical => true,
        _ => false,
      };
      if (wasOffline && next.valueOrNull == SyncStatus.online) {
        ref.invalidate(sellerLeaderboardProvider);
      }
    });

    final cs = Theme.of(context).colorScheme;
    final selection = ref.watch(selectedLeaderboardPeriodProvider);
    final period = selection.range();
    final metric = ref.watch(selectedLeaderboardMetricProvider);
    final isOffline = switch (ref.watch(syncStatusProvider).valueOrNull) {
      SyncStatus.offlineOk || SyncStatus.offlineCritical => true,
      _ => false,
    };

    // The metric is not part of the fetch key: the toggle re-ranks locally.
    final params = SellerLeaderboardParams(
      from: period.from,
      to: period.to,
      storeId: storeId,
    );
    final dataAsync = ref.watch(sellerLeaderboardProvider(params));

    return Scaffold(
      backgroundColor: cs.surfaceContainerLowest,
      body: CustomScrollView(
        slivers: [
          SliverAppBar(
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            automaticallyImplyLeading: false,
            backgroundColor: cs.surface,
            title: Text(
              'Classement vendeurs',
              style: TextStyle(
                color: cs.onSurface,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.2,
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: ColoredBox(
              color: cs.surface,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    PeriodFilterChips(
                      selected: selection,
                      onChanged: (s) => ref
                          .read(selectedLeaderboardPeriodProvider.notifier)
                          .state = s,
                    ),
                    const SizedBox(height: 4),
                    RankingMetricToggle(
                      selected: metric,
                      onChanged: (m) => ref
                          .read(selectedLeaderboardMetricProvider.notifier)
                          .state = m,
                    ),
                  ],
                ),
              ),
            ),
          ),
          // Banner whenever the shown data is the local Drift aggregation:
          // device offline, or online but the backend was unreachable.
          if (isOffline || (dataAsync.valueOrNull?.fromLocal ?? false))
            const SliverToBoxAdapter(child: OfflineDataBanner()),
          dataAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: AppErrorWidget(
                error: e,
                title: 'Impossible de charger le classement',
                onRetry: () => ref.invalidate(sellerLeaderboardProvider),
              ),
            ),
            data: (result) {
              final entries = rankSellersBy(result.entries, metric);
              if (entries.isEmpty) {
                return SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SoftIconBadge(
                            icon: Icons.groups_outlined, size: 72),
                        const SizedBox(height: 12),
                        Text(
                          'Aucune vente sur la période',
                          style: TextStyle(color: cs.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                );
              }
              return SliverList.separated(
                itemCount: entries.length,
                itemBuilder: (_, i) => SellerLeaderboardRow(
                  key: ValueKey('seller-${entries[i].sellerId}'),
                  entry: entries[i],
                ),
                separatorBuilder: (_, __) => const Divider(height: 1),
              );
            },
          ),
        ],
      ),
    );
  }
}
