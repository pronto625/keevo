import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';
import 'package:keevo_core/widget/soft_icon_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../audit/domain/audit_format.dart';
import '../../domain/model/stock_movement_model.dart';
import '../provider/stock_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';

/// StockHistoryPage — full-screen paginated stock movement history.
///
/// Shows a filtered, sortable list of stock movements for a product.
/// Supports filter by movement type.
///
/// Story 2.3.
class StockHistoryPage extends ConsumerStatefulWidget {
  final String productId;
  final String productName;

  const StockHistoryPage({
    super.key,
    required this.productId,
    required this.productName,
  });

  static Route<void> route({
    required String productId,
    required String productName,
  }) =>
      MaterialPageRoute(
        builder: (_) => StockHistoryPage(
          productId: productId,
          productName: productName,
        ),
      );

  @override
  ConsumerState<StockHistoryPage> createState() => _StockHistoryPageState();
}

const _kAllTypes = '__ALL__';

class _StockHistoryPageState extends ConsumerState<StockHistoryPage> {
  String? _filterType;
  DateTime _filterFrom = DateTime.now().subtract(const Duration(days: 7));
  DateTime _filterTo = DateTime.now();
  String? _filterStoreId;
  final _scrollController = ScrollController();
  int _page = 0;

  @override
  void initState() {
    super.initState();
    // Pre-filter by active store if one is selected in Settings.
    _filterStoreId = ref.read(activeStoreIdProvider);
    // Use microtask to defer state mutation until after the first build.
    // Calling loadHistory() directly from initState() triggers
    // state.copyWith() while the widget tree is building, which
    // Riverpod forbids ("Tried to modify a provider while building").
    Future.microtask(_loadHistory);
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadHistory({bool reset = false}) {
    if (reset) _page = 0;
    return ref
        .read(stockNotifierProvider(widget.productId).notifier)
        .loadHistory(
          movementType: _filterType,
          from: _filterFrom,
          to: _filterTo,
          storeId: _filterStoreId,
          page: _page,
        );
  }

  void _onScroll() {
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 100) {
      final state = ref.read(stockNotifierProvider(widget.productId));
      if (!state.isLoadingHistory && state.hasMoreHistory) {
        _page++;
        ref
            .read(stockNotifierProvider(widget.productId).notifier)
            .loadHistory(
              movementType: _filterType,
              from: _filterFrom,
              to: _filterTo,
              storeId: _filterStoreId,
              page: _page,
            );
      }
    }
  }

  bool _isDefaultDateRange() {
    final now = DateTime.now();
    final sevenDaysAgo = now.subtract(const Duration(days: 7));
    return _filterFrom.difference(sevenDaysAgo).inMinutes.abs() < 2 &&
        _filterTo.difference(now).inMinutes.abs() < 2;
  }

  Future<void> _pickDateRange() async {
    final picked = await showDateRangePicker(
      context: context,
      firstDate: DateTime(2020),
      lastDate: DateTime.now().add(const Duration(days: 1)),
      initialDateRange: DateTimeRange(start: _filterFrom, end: _filterTo),
      helpText: 'Sélectionner une période',
      cancelText: 'Annuler',
      confirmText: 'Confirmer',
    );
    if (picked != null) {
      setState(() {
        _filterFrom = picked.start;
        // End of selected day — include all movements that occurred during it.
        _filterTo = DateTime(
          picked.end.year,
          picked.end.month,
          picked.end.day,
          23,
          59,
          59,
          999,
        );
      });
      _loadHistory(reset: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final stockState = ref.watch(stockNotifierProvider(widget.productId));
    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded,
              color: cs.onSurface, size: 20),
          tooltip: MaterialLocalizations.of(context).backButtonTooltip,
          onPressed: () => Navigator.maybePop(context),
        ),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Historique des mouvements',
              style: TextStyle(
                color: cs.onSurface,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.2,
              ),
            ),
            Text(
              widget.productName,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: cs.onSurfaceVariant),
            ),
          ],
        ),
        actions: [
          // Date range picker
          SoftIconButton(
            tooltip: 'Période',
            icon: _isDefaultDateRange()
                ? Icons.calendar_month_outlined
                : Icons.calendar_month,
            iconSize: 20,
            onPressed: _pickDateRange,
          ),
          const SizedBox(width: 6),
          // Movement type filter
          Container(
            decoration: BoxDecoration(
              color: AppTheme.softTint(theme.brightness),
              borderRadius: BorderRadius.circular(14),
            ),
            child: PopupMenuButton<String>(
            icon: Icon(
              _filterType != null ? Icons.filter_alt : Icons.filter_alt_outlined,
              color: cs.primary,
              size: 20,
            ),
            onSelected: (value) {
              setState(() => _filterType = value == _kAllTypes ? null : value);
              _loadHistory(reset: true);
            },
            itemBuilder: (_) => const [
              // A null value would be treated as a dismissal by the menu.
              PopupMenuItem(value: _kAllTypes, child: Text('Tous')),
              PopupMenuItem(value: 'STOCK_ENTRY', child: Text('Entrées')),
              PopupMenuItem(value: 'SALE', child: Text('Ventes')),
              PopupMenuItem(value: 'ADJUSTMENT', child: Text('Ajustements')),
              PopupMenuItem(value: 'TRANSFER_IN', child: Text('Transferts entrants')),
              PopupMenuItem(value: 'TRANSFER_OUT', child: Text('Transferts sortants')),
              PopupMenuItem(value: 'SALE_CANCELLED', child: Text('Annulations')),
            ],
            ),
          ),
          const SizedBox(width: 12),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => _loadHistory(reset: true),
        child: stockState.movements.isEmpty && stockState.isLoadingHistory
            ? const Center(child: CircularProgressIndicator())
            : stockState.movements.isEmpty
                // Scrollable so pull-to-refresh works on an empty list.
                ? LayoutBuilder(
                    builder: (context, constraints) => SingleChildScrollView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      child: SizedBox(
                        height: constraints.maxHeight,
                        child: const Center(
                          child: Text('Aucun mouvement enregistré'),
                        ),
                      ),
                    ),
                  )
                : ListView.separated(
                    controller: _scrollController,
                    itemCount: stockState.movements.length +
                        (stockState.hasMoreHistory ? 1 : 0),
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, index) {
                      if (index == stockState.movements.length) {
                        return const Padding(
                          padding: EdgeInsets.all(16),
                          child: Center(child: CircularProgressIndicator()),
                        );
                      }
                      return _MovementTile(
                          movement: stockState.movements[index]);
                    },
                  ),
      ),
    );
  }
}

/// A single row in the movement history list.
class _MovementTile extends StatelessWidget {
  final StockMovementModel movement;

  const _MovementTile({required this.movement});

  @override
  Widget build(BuildContext context) {
    final isIn = movement.quantityDelta >= 0;
    final sign = isIn ? '+' : '';
    final color = isIn ? AppTheme.success : AppTheme.errorColor;
    final dateStr = DateFormat('dd/MM/yyyy HH:mm').format(movement.occurredAt);

    return ListTile(
      leading: SoftIconBadge(
        icon: isIn ? Icons.add_rounded : Icons.remove_rounded,
        color: color,
      ),
      title: Row(
        children: [
          Expanded(
            child: Text(
              _labelFor(movement.movementType),
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          Text(
            '$sign${movement.quantityDelta}',
            style: TextStyle(
              fontWeight: FontWeight.bold,
              color: color,
            ),
          ),
        ],
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(dateStr,
              style: TextStyle(
                  fontSize: 11,
                  color: Theme.of(context).colorScheme.onSurfaceVariant)),
          if (movement.notes != null && movement.notes!.isNotEmpty)
            Text(
              // v1s-16-20: `INVENTORY:<id>` → « Ajustement d'inventaire ».
              AuditFormat.notes(movement.notes)!,
              style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurfaceVariant),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
        ],
      ),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text('${movement.quantityBefore} → ${movement.quantityAfter}',
              style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurfaceVariant)),
        ],
      ),
    );
  }

  String _labelFor(String type) => switch (type) {
        'STOCK_ENTRY' => 'Entrée de stock',
        'SALE' => 'Vente',
        'ADJUSTMENT' => 'Ajustement',
        'TRANSFER_IN' => 'Transfert entrant',
        'TRANSFER_OUT' => 'Transfert sortant',
        'SALE_CANCELLED' => 'Vente annulée',
        _ => AuditFormat.humanize(type),
      };
}
