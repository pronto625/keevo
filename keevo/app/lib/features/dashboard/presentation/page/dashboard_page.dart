import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import 'package:keevo_core/widget/soft_icon_button.dart';

import '../../../../features/notifications/presentation/widget/notification_bell_widget.dart';
import '../../domain/model/dashboard_snapshot.dart';
import '../provider/dashboard_providers.dart';
import '../widget/metric_badge_card.dart';
import '../widget/morning_summary_hero_card.dart';
import '../widget/motivational_card.dart';
import '../widget/sales_evolution_chart.dart';
import '../widget/shop_status_card.dart';
import '../widget/top_products_section.dart';
import '../widget/total_revenue_card.dart';
import '../../../inventory/presentation/page/local_low_stock_page.dart';

/// DashboardPage — morning dashboard for OWNER role.
///
/// Story 7.1 — AC1-AC8, UX26-UX28.
class DashboardPage extends ConsumerWidget {
  const DashboardPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final snapshotAsync = ref.watch(dashboardSnapshotProvider);
    final firstName = ref.watch(currentUserFirstNameProvider) ?? 'Patron';
    final motivationalMessage = ref.watch(motivationalMessageProvider);

    return Scaffold(
      body: snapshotAsync.when(
        loading: () => const _SkeletonDashboard(),
        error: (err, _) => SafeArea(
          child: AppErrorWidget(
            error: err,
            title: 'Impossible de charger le tableau de bord',
            onRetry: () => ref.invalidate(dashboardSnapshotProvider),
          ),
        ),
        data: (snapshot) {
          // Empty state only when there is truly nothing to show: once a
          // store exists, its card, stock alerts and the bell stay reachable.
          final isEmpty = snapshot.todayCA == 0 &&
              snapshot.yesterdayCA == 0 &&
              snapshot.totalTransactionsMonth == 0 &&
              snapshot.prevMonthTransactions == 0 &&
              snapshot.storeOverviews.isEmpty &&
              snapshot.lowStockCount == 0 &&
              (snapshot.periodRevenue[RevenuePeriod.allTime]?.salesCount ??
                      0) ==
                  0;
          if (isEmpty) {
            return _EmptyState(
              firstName: firstName,
              onRefresh: () {
                ref.invalidate(dashboardSnapshotProvider);
                ref.invalidate(storeOverviewsProvider);
              },
            );
          }
          return _DashboardContent(
            snapshot: snapshot,
            firstName: firstName,
            motivationalMessage: motivationalMessage,
            onRefresh: () async {
              ref.invalidate(storeOverviewsProvider);
              // Keep the refresh spinner until the new data is in.
              try {
                ref.invalidate(dashboardSnapshotProvider);
                await ref.read(dashboardSnapshotProvider.future);
              } catch (_) {
                // The error state is rendered by the provider listener.
              }
            },
          );
        },
      ),
    );
  }
}

class _DashboardContent extends ConsumerWidget {
  final DashboardSnapshot snapshot;
  final String firstName;
  final String? motivationalMessage;
  final Future<void> Function() onRefresh;

  const _DashboardContent({
    required this.snapshot,
    required this.firstName,
    required this.motivationalMessage,
    required this.onRefresh,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final greeting = _buildGreeting(firstName);
    final tenantName = ref.watch(currentTenantNameProvider);
    // Online-first via snapshot; fallback to local when offline/empty.
    final topProducts = ref.watch(topProductsProvider).valueOrNull
        ?? snapshot.weeklyTopProducts;
    final worstProducts = ref.watch(worstProductsProvider).valueOrNull
        ?? snapshot.weeklyWorstProducts;
    final cs = Theme.of(context).colorScheme;

    return RefreshIndicator(
      onRefresh: onRefresh,
      child: CustomScrollView(
        slivers: [
          SliverAppBar(
            expandedHeight: tenantName != null && tenantName.isNotEmpty ? 72 : 56,
            floating: true,
            snap: true,
            // Fond neutre opaque : le thème rend l'AppBar transparente, ce
            // qui laisserait le contenu défiler visiblement sous l'en-tête.
            backgroundColor: cs.surface,
            surfaceTintColor: Colors.transparent,
            scrolledUnderElevation: 0,
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  greeting,
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.3,
                        color: cs.onSurface,
                      ),
                ),
                if (tenantName != null && tenantName.isNotEmpty)
                  Text(
                    'Prêt pour une journée productive au $tenantName ?',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: cs.onSurfaceVariant,
                        ),
                  ),
              ],
            ),
            actions: [
              const NotificationBellWidget(),
              const SizedBox(width: 6),
              SoftIconButton(
                icon: Icons.refresh_rounded,
                onPressed: onRefresh,
                tooltip: 'Actualiser',
              ),
              const SizedBox(width: 12),
            ],
          ),
          SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // « Chiffre d'affaires total » — period selectable, first.
                _TotalRevenueSection(snapshotAsOf: snapshot.asOf),

                // Hero Card — AC2
                MorningSummaryHeroCard(
                  todayCA: snapshot.todayCA,
                  trendPercent: snapshot.heroTrendPercent,
                  last7Days: snapshot.last7DaysCA,
                  lastUpdated: snapshot.asOf ?? DateTime.now(),
                ),
                const SizedBox(height: 8),

                // Motivational Message — AC5
                if (motivationalMessage != null)
                  MotivationalCard(
                    message: motivationalMessage!,
                    onDismiss: () async {
                      final prefs = ref.read(sharedPreferencesProvider);
                      await dismissMotivationalMessage(prefs);
                      ref.invalidate(isMotivationalDismissedProvider);
                    },
                  ),

                const SizedBox(height: 8),

                // Metric Badges — AC3
                _MetricGrid(snapshot: snapshot),

                const SizedBox(height: 16),

                // Store Overviews — AC4
                if (snapshot.storeOverviews.isNotEmpty) ...[
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Vos boutiques',
                          style: Theme.of(context)
                              .textTheme
                              .titleSmall
                              ?.copyWith(fontWeight: FontWeight.w700),
                        ),
                        GestureDetector(
                          onTap: () => context.push('/stores'),
                          child: Text(
                            'VOIR TOUT',
                            style: Theme.of(context).textTheme.labelSmall?.copyWith(
                                  color: Theme.of(context).colorScheme.primary,
                                  fontWeight: FontWeight.w600,
                                ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 8),
                  ...snapshot.storeOverviews.map(
                    (store) => Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 4,
                      ),
                      child: ShopStatusCard(
                        store: store,
                        onTap: () =>
                            context.push('/dashboard/store/${store.storeId}'),
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                ],

                // Sales Evolution Chart — AC8
                _ChartWithFilter(dailyData: snapshot.dailyCALast30),

                // Top Products — AC7
                TopProductsSection(
                  products: topProducts,
                  onViewAll: () => context.go('/reports'),
                ),

                const SizedBox(height: 16),

                // Worst Products
                if (worstProducts.isNotEmpty)
                  TopProductsSection(
                    title: 'Moins vendus — toutes boutiques (7j)',
                    products: worstProducts,
                  ),

                const SizedBox(height: 24),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _buildGreeting(String name) {
    final hour = DateTime.now().hour;
    if (hour < 12) return 'Bonjour $name';
    if (hour < 18) return 'Bon après-midi $name';
    return 'Bonsoir $name';
  }
}

/// « Chiffre d'affaires total » card wired to its providers.
class _TotalRevenueSection extends ConsumerWidget {
  /// [DashboardSnapshot.asOf] of the snapshot on screen.
  final DateTime? snapshotAsOf;

  const _TotalRevenueSection({required this.snapshotAsOf});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final period = ref.watch(revenuePeriodProvider);
    final view = ref.watch(periodRevenueProvider).valueOrNull;
    // While another period — or a newer snapshot (sign-in, refresh) — loads,
    // show the loading dash, never the previous figure (v1s-16-21).
    final current = view != null &&
            view.period == period &&
            view.asOf == snapshotAsOf
        ? view
        : null;
    return TotalRevenueCard(
      period: period,
      revenue: current?.revenue,
      onDeviceOnly: current?.onDeviceOnly ?? false,
      onPeriodChanged: (p) =>
          ref.read(revenuePeriodProvider.notifier).select(p),
    );
  }
}

/// Metric grid — responsive 2xN layout of metric badge cards.
class _MetricGrid extends ConsumerWidget {
  final DashboardSnapshot snapshot;
  const _MetricGrid({required this.snapshot});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Alignée sur la même marge que la hero card / les autres sections
    // (16dp, pas 12) + espacement généreux entre cartes (12dp, pas 4) —
    // les cartes étaient collées les unes aux autres.
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final crossAxisCount = constraints.maxWidth > 600 ? 4 : 2;
          return GridView.count(
            crossAxisCount: crossAxisCount,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            childAspectRatio: 1.25,
            mainAxisSpacing: 12,
            crossAxisSpacing: 12,
            children: [
              MetricBadgeCard.yesterdayCA(amount: snapshot.yesterdayCA),
              MetricBadgeCard.lowStock(
                count: snapshot.lowStockCount,
                // 13.10b: multi-store overview for Premium, free local list
                // otherwise.
                // The tile counts every store: the local list too.
                onTap: () => openLowStockView(context, allStores: true),
              ),
              MetricBadgeCard.monthlyTransactions(
                count: snapshot.totalTransactionsMonth,
                trendPercent: snapshot.transactionsTrend,
                onTap: () => context.push('/pos/sales-history'),
              ),
              MetricBadgeCard.averageBasket(
                amount: snapshot.averageBasketMonth,
                trendPercent: snapshot.averageBasketTrend,
              ),
            ],
          );
        },
      ),
    );
  }
}

/// Chart with period filter — delegates data fetching to provider.
class _ChartWithFilter extends ConsumerWidget {
  final List<DailyCA> dailyData;
  const _ChartWithFilter({required this.dailyData});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final period = ref.watch(chartPeriodProvider);
    final days = ref.watch(chartDaysProvider);
    final chartAsync = ref.watch(chartDataProvider);

    final data = chartAsync.when(
      data: (d) => d,
      loading: () {
        final all = dailyData;
        return all.length > days ? all.sublist(all.length - days) : all;
      },
      error: (_, __) {
        final all = dailyData;
        return all.length > days ? all.sublist(all.length - days) : all;
      },
    );

    if (data.isEmpty) return const SizedBox.shrink();

    return Column(
      children: [
        SalesEvolutionChart(
          data: data,
          period: period,
          daysRange: days,
          onPeriodChanged: (p) => ref.read(chartPeriodProvider.notifier).state = p,
          onDaysRangeChanged: period == ChartPeriod.daily
              ? (d) => ref.read(chartDaysProvider.notifier).state = d
              : null,
        ),
        const SizedBox(height: 16),
      ],
    );
  }
}

/// Skeleton shimmer loading state.
class _SkeletonDashboard extends StatelessWidget {
  const _SkeletonDashboard();

  @override
  Widget build(BuildContext context) {
    return CustomScrollView(
      slivers: [
        SliverAppBar(
          floating: true,
          backgroundColor: Theme.of(context).colorScheme.surface,
          surfaceTintColor: Colors.transparent,
          scrolledUnderElevation: 0,
          title: const _SkeletonBox(width: 200, height: 24),
        ),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                // Total revenue skeleton
                const _SkeletonBox(width: double.infinity, height: 140),
                const SizedBox(height: 16),
                // Hero skeleton
                const _SkeletonBox(width: double.infinity, height: 160),
                const SizedBox(height: 16),
                // Metric grid skeleton
                GridView.count(
                  crossAxisCount: 2,
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  childAspectRatio: 1.5,
                  mainAxisSpacing: 8,
                  crossAxisSpacing: 8,
                  children: List.generate(
                    4,
                    (_) =>
                        const _SkeletonBox(width: double.infinity, height: 80),
                  ),
                ),
                const SizedBox(height: 16),
                // Chart skeleton
                const _SkeletonBox(width: double.infinity, height: 130),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _SkeletonBox extends StatelessWidget {
  final double width;
  final double height;
  const _SkeletonBox({required this.width, required this.height});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: Theme.of(context)
            .colorScheme
            .onSurface
            .withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
      ),
    );
  }
}

/// Error state with retry button.
class _EmptyState extends StatelessWidget {
  final String firstName;
  final VoidCallback onRefresh;
  const _EmptyState({required this.firstName, required this.onRefresh});

  @override
  Widget build(BuildContext context) {
    final greeting = firstName.isNotEmpty ? 'Bonjour $firstName !' : 'Bonjour !';
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SoftIconBadge(icon: Icons.storefront_outlined, size: 88),
            const SizedBox(height: 16),
            Text(
              greeting,
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                    letterSpacing: -0.3,
                  ),
            ),
            const SizedBox(height: 8),
            Text(
              'Aucune vente enregistrée pour le moment.\nCommencez à vendre pour voir vos statistiques ici !',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: onRefresh,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Actualiser'),
            ),
          ],
        ),
      ),
    );
  }
}
