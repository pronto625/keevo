import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../profitability/presentation/page/product_profitability_list_page.dart';
import '../../../profitability/presentation/page/store_performance_page.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import 'report_history_page.dart';
import 'reports_page.dart';

/// OwnerReportsPage — AC7 of Story 7.4.
///
/// 4-tab tabbed reports hub for OWNER role:
///   📋 Jour         — existing ReportsPage (day-close summary + closure)
///   🕐 Historique   — existing ReportHistoryPage
///   📈 Rentabilité  — ProductProfitabilityListPage (Story 7.4)
///   🏪 Boutiques    — StorePerformancePage (Story 7.4)
///   📝 Brouillons   — PendingSalesPage
///
/// Uses a StatefulWidget with an explicit TabController to keep it stable across
/// provider-driven rebuilds, preventing GlobalKey conflicts that can arise when
/// DefaultTabController is embedded in a ConsumerWidget that rebuilds.
///
/// EMPLOYEE route `/reports` points to [EmployeeReportsPage] (Jour + Brouillons).
class OwnerReportsPage extends ConsumerStatefulWidget {
  const OwnerReportsPage({super.key});

  @override
  ConsumerState<OwnerReportsPage> createState() => _OwnerReportsPageState();
}

class _OwnerReportsPageState extends ConsumerState<OwnerReportsPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 4, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final activeStoreId = ref.watch(activeStoreIdProvider);
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: context.canPop()
            ? IconButton(
                icon: const Icon(Icons.arrow_back_ios_new_rounded,
                    color: Colors.white),
                onPressed: () => context.pop(),
              )
            : null,
        backgroundColor: AppTheme.primary,
        title: const Text(
          'Rapports',
          style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
        ),
        bottom: TabBar(
          controller: _tabController,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white60,
          indicatorColor: Colors.white,
          tabs: const [
            Tab(icon: Icon(Icons.today_outlined), text: 'Jour'),
            Tab(icon: Icon(Icons.history_outlined), text: 'Historique'),
            Tab(icon: Icon(Icons.trending_up_outlined), text: 'Rentabilité'),
            Tab(icon: Icon(Icons.storefront_outlined), text: 'Boutiques'),
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
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: context.canPop()
            ? IconButton(
                icon: const Icon(Icons.arrow_back_ios_new_rounded,
                    color: Colors.white),
                onPressed: () => context.pop(),
              )
            : null,
        backgroundColor: AppTheme.primary,
        title: const Text(
          'Rapports',
          style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
        ),
        bottom: TabBar(
          controller: _tabController,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white60,
          indicatorColor: Colors.white,
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

