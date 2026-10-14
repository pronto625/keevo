import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

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
class OwnerReportsPage extends ConsumerStatefulWidget {
  /// Tab opened first (AC7 deep links): 0 Jour, 1 Historique,
  /// 2 Rentabilité (`/reports/rentabilite`), 3 Boutiques (`/reports/boutiques`),
  /// 4 Équipe.
  final int initialTab;

  const OwnerReportsPage({super.key, this.initialTab = 0});

  static const tabRentabilite = 2;
  static const tabBoutiques = 3;
  static const tabEquipe = 4;

  @override
  ConsumerState<OwnerReportsPage> createState() => _OwnerReportsPageState();
}

class _OwnerReportsPageState extends ConsumerState<OwnerReportsPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(
      length: 5,
      vsync: this,
      initialIndex: widget.initialTab.clamp(0, 4),
    );
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final activeStoreId = ref.watch(activeStoreIdProvider);
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: context.canPop()
            ? IconButton(
                icon: Icon(Icons.arrow_back_ios_new_rounded,
                    color: cs.onSurface),
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
          tabs: [
            _hubTab(Icons.today_outlined, 'Jour'),
            _hubTab(Icons.history_outlined, 'Historique'),
            _hubTab(Icons.trending_up_outlined, 'Marges'),
            _hubTab(Icons.storefront_outlined, 'Boutiques'),
            _hubTab(Icons.groups_outlined, 'Équipe'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        // Stable ValueKeys prevent Flutter from confusing instances across
        // rebuilds, especially with AutomaticKeepAliveClientMixin active.
        children: [
          _KeepAliveTab(key: const ValueKey('tab-jour'), child: ReportsPage()),
          _KeepAliveTab(
              key: const ValueKey('tab-historique'),
              child: ReportHistoryPage(storeId: activeStoreId)),
          _KeepAliveTab(
              key: const ValueKey('tab-rentabilite'),
              child: ProductProfitabilityListPage(storeId: activeStoreId)),
          const _KeepAliveTab(
              key: ValueKey('tab-boutiques'), child: StorePerformancePage()),
          _KeepAliveTab(
              key: const ValueKey('tab-equipe'),
              child: SellerLeaderboardPage(storeId: activeStoreId)),
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
                icon: Icon(Icons.arrow_back_ios_new_rounded,
                    color: cs.onSurface),
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
          _KeepAliveTab(
              key: ValueKey('emp-tab-jour'), child: ReportsPage()),
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
