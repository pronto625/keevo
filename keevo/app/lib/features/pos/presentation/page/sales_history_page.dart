import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../../core/di/providers.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../team/presentation/provider/employee_provider.dart';
import '../../domain/model/payment_mode_enum.dart';
import '../../domain/model/sale_model.dart';
import '../../domain/model/sales_history_filter.dart';
import '../provider/day_closure_providers.dart';

/// SalesHistoryPage — Displays sales history filtered by date.
///
/// Story 4.4 AC7 — Historique des ventes (Mes Ventes).
/// adminMode=true: OWNER view of all store sales (from Détails Boutique).
/// adminMode=false: EMPLOYEE sees own sales (or OWNER sees active store).
class SalesHistoryPage extends ConsumerStatefulWidget {
  /// Override active store (used when launched from store detail admin view).
  final String? storeId;

  /// When true: OWNER context, show all employees' sales + employee filter.
  final bool adminMode;

  const SalesHistoryPage({
    super.key,
    this.storeId,
    this.adminMode = false,
  });

  @override
  ConsumerState<SalesHistoryPage> createState() => _SalesHistoryPageState();
}

class _SalesHistoryPageState extends ConsumerState<SalesHistoryPage> {
  DateFilterType _selectedFilter = DateFilterType.today;
  DateTimeRange? _customRange;
  String? _selectedEmployeeId; // only used in adminMode

  final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );
  final _timeFormat = DateFormat('HH:mm', 'fr_FR');
  final _dateFormat = DateFormat('dd MMM yyyy', 'fr_FR');

  @override
  Widget build(BuildContext context) {
    final activeStoreId = ref.watch(activeStoreIdProvider) ?? 'default';
    final effectiveStoreId = widget.storeId ?? activeStoreId;
    final userIdAsync = ref.watch(currentUserIdProvider);

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.adminMode ? 'Ventes de la boutique' : 'Mes Ventes'),
        backgroundColor: AppTheme.primary,
        foregroundColor: Colors.white,
        elevation: 0,
      ),
      body: userIdAsync.when(
        data: (userId) {
          if (userId == null) {
            return const Center(child: Text('Utilisateur non connecté'));
          }
          return _buildContent(effectiveStoreId, userId);
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(error: e),
      ),
    );
  }

  Widget _buildContent(String storeId, String employeeId) {
    final role = ref.watch(currentUserRoleProvider);
    // adminMode: OWNER view of all store sales; employee filter is optional.
    // Normal: EMPLOYEE sees own sales, OWNER sees all store sales.
    final String? effectiveEmployeeId;
    if (widget.adminMode) {
      effectiveEmployeeId = _selectedEmployeeId; // can be null (all) or specific
    } else {
      effectiveEmployeeId = role == 'OWNER' ? null : employeeId;
    }
    final filter = _buildFilter(storeId, effectiveEmployeeId);
    final salesAsync = ref.watch(salesHistoryProvider(filter));

    return Column(
      children: [
        // Employee filter (adminMode only)
        if (widget.adminMode) _buildEmployeeFilter(storeId),
        // Date filter chips
        _buildFilterBar(),
        // Sales list
        Expanded(
          child: salesAsync.when(
            data: (sales) => sales.isEmpty
                ? _buildEmptyState()
                : _buildSalesList(sales),
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => AppErrorWidget(error: e),
          ),
        ),
      ],
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
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          color: Colors.white,
          child: Row(
            children: [
              const Icon(Icons.person_outline, size: 18),
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
                      child: Text('Tous les employés',
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

  Widget _buildFilterBar() {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: Colors.white,
        boxShadow: [
          BoxShadow(
            color: cs.shadow.withValues(alpha: 0.05),
            blurRadius: 4,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            _buildFilterChip(DateFilterType.today, "Aujourd'hui"),
            const SizedBox(width: 8),
            _buildFilterChip(DateFilterType.thisWeek, 'Cette semaine'),
            const SizedBox(width: 8),
            _buildFilterChip(DateFilterType.thisMonth, 'Ce mois'),
            const SizedBox(width: 8),
            _buildCustomFilterChip(),
          ],
        ),
      ),
    );
  }

  Widget _buildFilterChip(DateFilterType type, String label) {
    final isSelected = _selectedFilter == type;
    final cs = Theme.of(context).colorScheme;
    return FilterChip(
      label: Text(label),
      selected: isSelected,
      onSelected: (_) {
        setState(() {
          _selectedFilter = type;
          _customRange = null;
        });
      },
      backgroundColor: cs.surfaceContainerHighest,
      selectedColor: AppTheme.primary.withValues(alpha: 0.2),
      checkmarkColor: AppTheme.primary,
      labelStyle: TextStyle(
        color: isSelected ? AppTheme.primary : cs.onSurface,
        fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
      ),
    );
  }

  Widget _buildCustomFilterChip() {
    final isSelected = _selectedFilter == DateFilterType.custom;
    final cs = Theme.of(context).colorScheme;
    final label = isSelected && _customRange != null
        ? '${_dateFormat.format(_customRange!.start)} - ${_dateFormat.format(_customRange!.end)}'
        : 'Personnalisé';

    return FilterChip(
      label: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label),
          const SizedBox(width: 4),
          const Icon(Icons.date_range, size: 16),
        ],
      ),
      selected: isSelected,
      onSelected: (_) => _selectCustomRange(),
      backgroundColor: cs.surfaceContainerHighest,
      selectedColor: AppTheme.primary.withValues(alpha: 0.2),
      checkmarkColor: AppTheme.primary,
      labelStyle: TextStyle(
        color: isSelected ? AppTheme.primary : cs.onSurface,
        fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
      ),
    );
  }

  Future<void> _selectCustomRange() async {
    final now = DateTime.now();
    final range = await showDateRangePicker(
      context: context,
      firstDate: now.subtract(const Duration(days: 365)),
      lastDate: now,
      initialDateRange: _customRange ??
          DateTimeRange(
            start: now.subtract(const Duration(days: 7)),
            end: now,
          ),
      locale: const Locale('fr', 'FR'),
      builder: (context, child) {
        return Theme(
          data: Theme.of(context).copyWith(
            colorScheme: const ColorScheme.light(
              primary: AppTheme.primary,
              onPrimary: Colors.white,
            ),
          ),
          child: child!,
        );
      },
    );

    if (range != null) {
      setState(() {
        _selectedFilter = DateFilterType.custom;
        _customRange = range;
      });
    }
  }

  SalesHistoryFilter _buildFilter(String storeId, String? employeeId) {
    switch (_selectedFilter) {
      case DateFilterType.today:
        return SalesHistoryFilter.today(
          storeId: storeId,
          employeeId: employeeId,
        );
      case DateFilterType.thisWeek:
        return SalesHistoryFilter.thisWeek(
          storeId: storeId,
          employeeId: employeeId,
        );
      case DateFilterType.thisMonth:
        return SalesHistoryFilter.thisMonth(
          storeId: storeId,
          employeeId: employeeId,
        );
      case DateFilterType.custom:
        if (_customRange != null) {
          return SalesHistoryFilter.custom(
            storeId: storeId,
            employeeId: employeeId,
            from: _customRange!.start,
            to: DateTime(
              _customRange!.end.year,
              _customRange!.end.month,
              _customRange!.end.day,
              23,
              59,
              59,
            ),
          );
        }
        return SalesHistoryFilter.today(
          storeId: storeId,
          employeeId: employeeId,
        );
    }
  }

  Widget _buildEmptyState() {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.receipt_long_outlined,
            size: 64,
            color: cs.outline,
          ),
          const SizedBox(height: 16),
          Text(
            'Aucune vente pour cette période',
            style: TextStyle(
              fontSize: 16,
              color: cs.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSalesList(List<Sale> sales) {
    return RefreshIndicator(
      onRefresh: () async {
        // Invalidate the provider to trigger a refresh
        ref.invalidate(salesHistoryProvider);
      },
      child: ListView.builder(
        padding: const EdgeInsets.all(16),
        itemCount: sales.length,
        itemBuilder: (context, index) {
          final sale = sales[index];
          return _SaleHistoryCard(
            sale: sale,
            currencyFormat: _currencyFormat,
            timeFormat: _timeFormat,
            onTap: () => context.push('/pos/sales-history/${sale.id}', extra: sale),
          );
        },
      ),
    );
  }
}

/// SaleHistoryCard — Displays a single sale in the history list.
///
/// Story 4.4 AC7 — Card layout with status badges.
class _SaleHistoryCard extends StatelessWidget {
  final Sale sale;
  final NumberFormat currencyFormat;
  final DateFormat timeFormat;
  final VoidCallback onTap;

  const _SaleHistoryCard({
    required this.sale,
    required this.currencyFormat,
    required this.timeFormat,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      elevation: 1,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              // Payment mode icon
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: _paymentModeColor.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  _paymentModeIcon,
                  color: _paymentModeColor,
                  size: 24,
                ),
              ),
              const SizedBox(width: 16),
              // Sale details
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          timeFormat.format(sale.occurredAt),
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 15,
                          ),
                        ),
                        const SizedBox(width: 8),
                        _buildStatusBadge(),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${sale.items.length} article${sale.items.length > 1 ? 's' : ''}',
                      style: TextStyle(
                        color: cs.onSurfaceVariant,
                        fontSize: 13,
                      ),
                    ),
                    if (sale.clientId != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        'Client: ${sale.clientId}', // TODO: resolve client name
                        style: TextStyle(
                          color: cs.onSurfaceVariant,
                          fontSize: 12,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              // Total amount
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    currencyFormat.format(sale.totalAmount),
                    style: const TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 16,
                      color: AppTheme.primary,
                    ),
                  ),
                  if (sale.discountAmount > 0) ...[
                    const SizedBox(height: 2),
                    Text(
                      '-${currencyFormat.format(sale.discountAmount)}',
                      style: TextStyle(
                        color: AppTheme.warning,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ],
              ),
              const SizedBox(width: 8),
              Icon(
                Icons.chevron_right,
                color: cs.outline,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStatusBadge() {
    Color color;
    String label;

    switch (sale.status) {
      case 'PENDING_VALIDATION':
        color = AppTheme.warning;
        label = 'En attente';
        break;
      case 'CANCELLED':
        color = AppTheme.errorColor;
        label = 'Annulée';
        break;
      default:
        // COMPLETED — no badge needed
        return const SizedBox.shrink();
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.2),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  IconData get _paymentModeIcon {
    switch (sale.paymentMode) {
      case PaymentModeEnum.mobileMoney:
        return Icons.phone_android;
      case PaymentModeEnum.cash:
        return Icons.payments_outlined;
    }
  }

  Color get _paymentModeColor {
    switch (sale.paymentMode) {
      case PaymentModeEnum.mobileMoney:
        return AppTheme.warning;
      case PaymentModeEnum.cash:
        return AppTheme.success;
    }
  }
}
