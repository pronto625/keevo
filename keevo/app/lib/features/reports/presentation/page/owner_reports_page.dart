import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
///
/// EMPLOYEE route `/reports` still points to [ReportsPage] (no tabs).
class OwnerReportsPage extends ConsumerWidget {
  const OwnerReportsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final activeStoreId = ref.watch(activeStoreIdProvider);
    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          automaticallyImplyLeading: false,
          backgroundColor: const Color(0xFF3B5BDB),
          title: const Text(
            'Rapports',
            style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
          ),
          bottom: const TabBar(
            labelColor: Colors.white,
            unselectedLabelColor: Colors.white60,
            indicatorColor: Colors.white,
            tabs: [
              Tab(icon: Icon(Icons.today_outlined), text: 'Jour'),
              Tab(icon: Icon(Icons.history_outlined), text: 'Historique'),
              Tab(icon: Icon(Icons.trending_up_outlined), text: 'Rentabilité'),
              Tab(icon: Icon(Icons.storefront_outlined), text: 'Boutiques'),
            ],
          ),
        ),
        body: TabBarView(
          // Keep each tab alive so state (scroll position, filters) is preserved
          children: [
            _KeepAliveTab(child: ReportsPage()),
            _KeepAliveTab(child: ReportHistoryPage(storeId: activeStoreId)),
            _KeepAliveTab(child: ProductProfitabilityListPage(storeId: activeStoreId)),
            _KeepAliveTab(child: StorePerformancePage()),
          ],
        ),
      ),
    );
  }
}

// ── KeepAlive wrapper ─────────────────────────────────────────────────────────

class _KeepAliveTab extends StatefulWidget {
  final Widget child;
  const _KeepAliveTab({required this.child});

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
