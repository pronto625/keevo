import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../team/presentation/provider/employee_provider.dart';
import '../provider/report_history_providers.dart';
import '../widget/report_history_card.dart';

/// ReportHistoryPage — displays paginated list of end-of-day reports.
/// Story 7.2 — Task 21.
///
/// adminMode=false (default): each role sees appropriate reports.
///   EMPLOYEE: only their own reports (actorId forced by backend).
///   OWNER: all reports for active store.
/// adminMode=true: OWNER view of all reports for a specific store
///   (launched from Détails Boutique). Can filter by employee.
class ReportHistoryPage extends ConsumerStatefulWidget {
  /// Filter to a specific store (admin view from store detail).
  final String? storeId;

  /// When true: admin context — aggregated store view + employee filter.
  final bool adminMode;

  const ReportHistoryPage({
    super.key,
    this.storeId,
    this.adminMode = false,
  });

  @override
  ConsumerState<ReportHistoryPage> createState() => _ReportHistoryPageState();
}

class _ReportHistoryPageState extends ConsumerState<ReportHistoryPage> {
  String? _selectedEmployeeId;
  String? _typeFilter; // null=Tous, 'DAILY'=Journalier, 'WEEKLY'=Hebdomadaire

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final reportsAsync = ref.watch(reportHistoryProvider(
      storeId: widget.storeId,
      actorId: widget.adminMode ? _selectedEmployeeId : null,
      type: _typeFilter,
    ));

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.adminMode
            ? 'Rapports de la boutique'
            : 'Historique des rapports'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.pop(),
        ),
      ),
      body: Column(
        children: [
          // Report type filter tabs (Tous | Journalier | Hebdomadaire)
          _buildTypeFilterTabs(),
          // Employee filter (adminMode only)
          if (widget.adminMode && widget.storeId != null)
            _buildEmployeeFilter(widget.storeId!),
          // Report list
          Expanded(
            child: reportsAsync.when(
              loading: () => const _ReportListSkeleton(),
              error: (e, _) => AppErrorWidget(
                error: e,
                onRetry: () => ref.invalidate(reportHistoryProvider),
              ),
              data: (reports) {
                if (reports.isEmpty) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.bar_chart_outlined,
                            size: 64, color: theme.colorScheme.outlineVariant),
                        const SizedBox(height: 16),
                        Text(
                          'Aucun rapport disponible.',
                          style: theme.textTheme.titleMedium?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Les rapports apparaîtront après la première clôture journalière.',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  );
                }
                return RefreshIndicator(
                  onRefresh: () async => ref.invalidate(reportHistoryProvider),
                  child: ListView.builder(
                    itemCount: reports.length,
                    itemBuilder: (context, index) {
                      final report = reports[index];
                      return ReportHistoryCard(
                        report: report,
                        onTap: () => context.push(
                            '/reports/history/${report.id}',
                            extra: report),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTypeFilterTabs() {
    final tabs = [
      (label: 'Tous', value: null),
      (label: 'Journalier', value: 'DAILY'),
      (label: '📅 Hebdo', value: 'WEEKLY'),
    ];
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: tabs.map((tab) {
          final isSelected = _typeFilter == tab.value;
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Text(tab.label),
              selected: isSelected,
              onSelected: (_) => setState(() => _typeFilter = tab.value),
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildEmployeeFilter(String storeId) {
    final employeesAsync = ref.watch(employeeListProvider);
    return employeesAsync.when(
      data: (employees) {
        final storeEmployees =
            employees.where((e) => e.storeId == storeId).toList();
        if (storeEmployees.isEmpty) return const SizedBox.shrink();
        return Container(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 4),
          decoration: BoxDecoration(
            color: Colors.white,
            boxShadow: [
              BoxShadow(
                color: Theme.of(context).colorScheme.shadow.withValues(alpha: 0.04),
                blurRadius: 4,
                offset: const Offset(0, 2),
              ),
            ],
          ),
          child: Row(
            children: [
              Icon(Icons.person_outline, size: 18, color: Theme.of(context).colorScheme.onSurfaceVariant),
              const SizedBox(width: 8),
              Expanded(
                child: DropdownButton<String?>(
                  value: _selectedEmployeeId,
                  isExpanded: true,
                  underline: const SizedBox.shrink(),
                  hint: const Text('Tous les employés',
                      style: TextStyle(fontSize: 14)),
                  items: [
                    const DropdownMenuItem<String?>(
                      value: null,
                      child: Text('Rapport global (boutique)',
                          style: TextStyle(fontSize: 14)),
                    ),
                    ...storeEmployees.map((e) => DropdownMenuItem<String?>(
                          value: e.userId,
                          child: Text('${e.firstName} ${e.lastName}',
                              style: const TextStyle(fontSize: 14)),
                        )),
                  ],
                  onChanged: (v) => setState(() => _selectedEmployeeId = v),
                ),
              ),
            ],
          ),
        );
      },
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
    );
  }
}

// ── Skeleton shimmer loading state ───────────────────────────────────────────

class _ReportListSkeleton extends StatelessWidget {
  const _ReportListSkeleton();

  @override
  Widget build(BuildContext context) {
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: 5,
      itemBuilder: (_, __) => const _SkeletonCard(),
    );
  }
}

class _SkeletonCard extends StatelessWidget {
  const _SkeletonCard();

  @override
  Widget build(BuildContext context) {
    final base = Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.08);
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      height: 88,
      decoration: BoxDecoration(
        color: base,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          Container(
            width: 6,
            decoration: BoxDecoration(
              color: base.withValues(alpha: 0.3),
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(16),
                bottomLeft: Radius.circular(16),
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(height: 14, width: 160, color: base),
                const SizedBox(height: 8),
                Container(height: 12, width: 100, color: base),
                const SizedBox(height: 6),
                Container(height: 12, width: 80, color: base),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
