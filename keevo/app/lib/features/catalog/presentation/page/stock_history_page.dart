import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

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

  void _loadHistory({bool reset = false}) {
    if (reset) _page = 0;
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

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Historique des mouvements'),
            Text(
              widget.productName,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: Colors.white70),
            ),
          ],
        ),
        actions: [
          // Date range picker
          IconButton(
            tooltip: 'Période',
            icon: Icon(
              _isDefaultDateRange()
                  ? Icons.calendar_month_outlined
                  : Icons.calendar_month,
            ),
            onPressed: _pickDateRange,
          ),
          // Movement type filter
          PopupMenuButton<String?>(
            icon: Icon(
              _filterType != null ? Icons.filter_alt : Icons.filter_alt_outlined,
            ),
            onSelected: (value) {
              setState(() => _filterType = value);
              _loadHistory(reset: true);
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: null, child: Text('Tous')),
              PopupMenuItem(value: 'STOCK_ENTRY', child: Text('Entrées')),
              PopupMenuItem(value: 'SALE', child: Text('Ventes')),
              PopupMenuItem(value: 'ADJUSTMENT', child: Text('Ajustements')),
              PopupMenuItem(value: 'TRANSFER_IN', child: Text('Transferts entrants')),
              PopupMenuItem(value: 'TRANSFER_OUT', child: Text('Transferts sortants')),
              PopupMenuItem(value: 'SALE_CANCELLED', child: Text('Annulations')),
            ],
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async => _loadHistory(reset: true),
        child: stockState.movements.isEmpty && stockState.isLoadingHistory
            ? const Center(child: CircularProgressIndicator())
            : stockState.movements.isEmpty
                ? const Center(
                    child: Text('Aucun mouvement enregistré'),
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
      leading: CircleAvatar(
        backgroundColor: isIn ? AppTheme.success : AppTheme.errorColor,
        child: Icon(
          isIn ? Icons.add_circle : Icons.remove_circle,
          color: color,
          size: 20,
        ),
      ),
      title: Row(
        children: [
          Expanded(child: Text(_labelFor(movement.movementType))),
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
          Text(dateStr, style: const TextStyle(fontSize: 11)),
          if (movement.notes != null && movement.notes!.isNotEmpty)
            Text(
              movement.notes!,
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
        _ => type,
      };
}
