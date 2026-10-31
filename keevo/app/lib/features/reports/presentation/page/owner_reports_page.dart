import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/router/module_routes.dart';
import '../../../export/domain/model/export_models.dart';
import '../../../export/presentation/widget/export_entry_button.dart';
import '../../../profitability/presentation/page/product_profitability_list_page.dart';
import '../../../profitability/presentation/page/seller_leaderboard_page.dart';
import '../../../profitability/presentation/page/store_performance_page.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import 'report_history_page.dart';
import 'reports_page.dart';

/// OwnerReportsPage — AC7 of Story 7.4.
///
/// 5-tab tabbed reports hub for OWNER role:
///   📋 Jour         — existing ReportsPage (day-close summary + closure)
///   🕐 Historique   — existing ReportHistoryPage
///   📈 Rentabilité  — ProductProfitabilityListPage (Story 7.4)
///   🏪 Boutiques    — StorePerformancePage (Story 7.4)
///   👥 Équipe       — SellerLeaderboardPage (FR44, v1s-14-2 — OWNER only)
///
/// Uses a StatefulWidget with an explicit TabController to keep it stable across
/// provider-driven rebuilds, preventing GlobalKey conflicts that can arise when
/// DefaultTabController is embedded in a ConsumerWidget that rebuilds.
///
/// EMPLOYEE route `/reports` points to [EmployeeReportsPage] (Jour + Brouillons).
class OwnerReportsPage extends ConsumerWidget {
  /// Tab opened first (AC7 deep links): 0 Jour, 1 Historique,
  /// 2 Rentabilité (`/reports/rentabilite`), 3 Boutiques (`/reports/boutiques`),
  /// 4 Équipe (`/reports/equipe`). Logical index: a tab hidden for its module
  /// (Story 15.2) opens « Jour » instead.
  final int initialTab;

  const OwnerReportsPage({super.key, this.initialTab = 0});

  static const tabJour = 0;
  static const tabHistorique = 1;
  static const tabRentabilite = 2;
  static const tabBoutiques = 3;
  static const tabEquipe = 4;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Story 15.2 — the Rentabilité / Boutiques tabs are the screens of the
    // `profitability` / `multi-store` routes: hidden when the module cannot
    // be read. Story 15.8 — same for Équipe (`advanced-reports`).
    final visible = [
      tabJour,
      tabHistorique,
      if (isRouteAvailable(ref, '/reports/rentabilite')) tabRentabilite,
      if (isRouteAvailable(ref, '/reports/boutiques')) tabBoutiques,
      if (isRouteAvailable(ref, '/reports/equipe')) tabEquipe,
    ];
    final initial = visible.indexOf(initialTab.clamp(0, tabEquipe));
    return _OwnerReportsHub(
      // A new tab set rebuilds the hub (its TabController has a fixed length).
      key: ValueKey(visible.join(',')),
      tabs: visible,
      initialIndex: initial < 0 ? 0 : initial,
    );
  }
}

class _OwnerReportsHub extends ConsumerStatefulWidget {
  /// Logical tab ids shown, in order.
  final List<int> tabs;
  final int initialIndex;

  const _OwnerReportsHub(
      {super.key, required this.tabs, required this.initialIndex});

  @override
  ConsumerState<_OwnerReportsHub> createState() => _OwnerReportsHubState();
}

class _OwnerReportsHubState extends ConsumerState<_OwnerReportsHub>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(
      length: widget.tabs.length,
      vsync: this,
      initialIndex: widget.initialIndex,
    );
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Widget _tab(int id) => switch (id) {
        OwnerReportsPage.tabJour => _hubTab(Icons.today_outlined, 'Jour'),
        OwnerReportsPage.tabHistorique =>
          _hubTab(Icons.history_outlined, 'Historique'),
        OwnerReportsPage.tabRentabilite =>
          _hubTab(Icons.trending_up_outlined, 'Marges'),
        OwnerReportsPage.tabBoutiques =>
          _hubTab(Icons.storefront_outlined, 'Boutiques'),
        _ => _hubTab(Icons.groups_outlined, 'Équipe'),
      };

  Widget _page(int id, String? activeStoreId) => switch (id) {
        OwnerReportsPage.tabJour =>
          _KeepAliveTab(key: const ValueKey('tab-jour'), child: ReportsPage()),
        OwnerReportsPage.tabHistorique => _KeepAliveTab(
            key: const ValueKey('tab-historique'),
            child: ReportHistoryPage(storeId: activeStoreId)),
        OwnerReportsPage.tabRentabilite => _KeepAliveTab(
            key: const ValueKey('tab-rentabilite'),
            child: ProductProfitabilityListPage(storeId: activeStoreId)),
        OwnerReportsPage.tabBoutiques => const _KeepAliveTab(
            key: ValueKey('tab-boutiques'), child: StorePerformancePage()),
        _ => _KeepAliveTab(
            key: const ValueKey('tab-equipe'),
            child: SellerLeaderboardPage(storeId: activeStoreId)),
      };

  @override
  Widget build(BuildContext context) {
    final activeStoreId = ref.watch(activeStoreIdProvider);
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: context.canPop()
            ? IconButton(
                icon:
                    Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
                onPressed: () => context.pop(),
              )
            : null,
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        title: Text(
          'Rapports',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
        actions: [
          // v1s-18-4 — « Exporter » sales, closures and reports.
          ExportEntryButton(
            key: const Key('reports-export'),
            prefill: ExportPrefill(
              datasets: const {
                'sales',
                'sale_items',
                'day_closures',
                'reports',
              },
              storeId: activeStoreId,
            ),
          ),
        ],
        bottom: TabBar(
          controller: _tabController,
          labelColor: cs.primary,
          unselectedLabelColor: cs.onSurfaceVariant,
          indicatorColor: cs.primary,
          dividerColor: cs.outlineVariant.withAlpha(80),
          // A31: five tabs on a 360 dp phone leave ~72 dp each — short labels,
          // a tighter padding and a slightly smaller font so none is cut
          // (« Historiq », « Rentabi » on device).
          labelPadding: const EdgeInsets.symmetric(horizontal: 4),
          labelStyle:
              const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
          unselectedLabelStyle:
              const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
          tabs: [for (final id in widget.tabs) _tab(id)],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        // Stable ValueKeys prevent Flutter from confusing instances across
        // rebuilds, especially with AutomaticKeepAliveClientMixin active.
        children: [
          for (final id in widget.tabs) _page(id, activeStoreId),
        ],
      ),
    );
  }
}

// ── KeepAlive wrapper ─────────────────────────────────────────────────────────

class _KeepAliveTab extends StatefulWidget {
  final Widget child;
  const _KeepAliveTab({required super.key, required this.child});

  @override
  State<_KeepAliveTab> createState() => _KeepAliveTabState();
}

class _KeepAliveTabState extends State<_KeepAliveTab>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return widget.child;
  }
}

// ── Employee reports page — Jour + Brouillons (B3.3) ─────────────────────────

/// EmployeeReportsPage — 2-tab hub for EMPLOYEE role.
///
///   📋 Jour       — ReportsPage (day summary, financial amounts hidden via B3.4)
///   📝 Brouillons — PendingSalesPage (store-scoped pending validation sales)
///
/// Rentabilité and Boutiques are intentionally absent — financial data is
/// OWNER-only (AC6 / HF-2).
class EmployeeReportsPage extends ConsumerStatefulWidget {
  const EmployeeReportsPage({super.key});

  @override
  ConsumerState<EmployeeReportsPage> createState() =>
      _EmployeeReportsPageState();
}

class _EmployeeReportsPageState extends ConsumerState<EmployeeReportsPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 1, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: context.canPop()
            ? IconButton(
                icon:
                    Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
                onPressed: () => context.pop(),
              )
            : null,
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        title: Text(
          'Rapports',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
        bottom: TabBar(
          controller: _tabController,
          labelColor: cs.primary,
          unselectedLabelColor: cs.onSurfaceVariant,
          indicatorColor: cs.primary,
          dividerColor: cs.outlineVariant.withAlpha(80),
          tabs: const [
            Tab(icon: Icon(Icons.today_outlined), text: 'Jour'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: const [
          _KeepAliveTab(key: ValueKey('emp-tab-jour'), child: ReportsPage()),
        ],
      ),
    );
  }
}

/// A hub tab whose label shrinks rather than being cut when the screen is
/// too narrow (A31). A [Tab] itself, so the bar keeps its icon + text height.
Tab _hubTab(IconData icon, String label) => Tab(
      icon: Icon(icon),
      child: FittedBox(
        fit: BoxFit.scaleDown,
        child: Text(label, maxLines: 1, softWrap: false),
      ),
    );
