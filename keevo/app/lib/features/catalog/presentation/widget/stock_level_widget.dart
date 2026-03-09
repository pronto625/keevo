import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/stock_level_model.dart';
import '../provider/stock_provider.dart';
import 'stock_entry_bottom_sheet.dart';
import 'stock_adjust_bottom_sheet.dart';
import 'stock_threshold_bottom_sheet.dart';

/// StockLevelWidget — shows per-store stock levels for a product.
///
/// Displays:
/// - Quantity per store
/// - Red "Stock bas" badge when isLow = true (quantity ≤ threshold)
/// - FAB-style action buttons for stock entry, adjustment, threshold config
///
/// Story 2.3.
class StockLevelWidget extends ConsumerWidget {
  final String productId;
  final String? storeId; // Optional: filter to one store
  final VoidCallback? onViewHistory;

  const StockLevelWidget({
    super.key,
    required this.productId,
    this.storeId,
    this.onViewHistory,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stockState = ref.watch(stockNotifierProvider(productId));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Header row
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              'Stock',
              style: Theme.of(context)
                  .textTheme
                  .titleMedium
                  ?.copyWith(fontWeight: FontWeight.bold),
            ),
            Row(
              children: [
                // Stock entry button
                IconButton(
                  icon: const Icon(Icons.add_box_outlined),
                  tooltip: 'Entrée de stock',
                  onPressed: () => StockEntryBottomSheet.show(
                    context,
                    ref,
                    productId: productId,
                    storeId: storeId,
                  ),
                ),
                // Adjust button
                IconButton(
                  icon: const Icon(Icons.edit_note),
                  tooltip: 'Ajuster le stock',
                  onPressed: () => StockAdjustBottomSheet.show(
                    context,
                    ref,
                    productId: productId,
                    storeId: storeId,
                  ),
                ),
                // Threshold config
                IconButton(
                  icon: const Icon(Icons.notifications_outlined),
                  tooltip: 'Seuil d\'alerte',
                  onPressed: () => StockThresholdBottomSheet.show(
                    context,
                    ref,
                    productId: productId,
                  ),
                ),
                // History
                if (onViewHistory != null)
                  IconButton(
                    icon: const Icon(Icons.history),
                    tooltip: 'Historique des mouvements',
                    onPressed: onViewHistory,
                  ),
              ],
            ),
          ],
        ),
        const Divider(height: 8),

        if (stockState.isLoading)
          const Padding(
            padding: EdgeInsets.all(16),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (stockState.error != null)
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text(
              'Erreur: ${stockState.error}',
              style: const TextStyle(color: Colors.red),
            ),
          )
        else if (stockState.levels.isEmpty)
          const Padding(
            padding: EdgeInsets.all(8),
            child: Text('Aucun stock enregistré', style: TextStyle(color: Colors.grey)),
          )
        else
          ...stockState.levels
              .where((l) => storeId == null || l.storeId == storeId)
              .map((level) => _StockLevelTile(level: level)),
      ],
    );
  }
}

/// Single store row in the stock level widget.
class _StockLevelTile extends StatelessWidget {
  final StockLevelModel level;

  const _StockLevelTile({required this.level});

  @override
  Widget build(BuildContext context) {
    final isLow = level.isLow;

    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        radius: 20,
        backgroundColor: isLow ? Colors.red.shade100 : Colors.green.shade100,
        child: Text(
          '${level.quantity}',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: isLow ? Colors.red.shade700 : Colors.green.shade700,
            fontSize: 13,
          ),
        ),
      ),
      title: Text(
        'Dépôt: ${level.storeId.substring(0, 8)}…',
        style: const TextStyle(fontSize: 13),
      ),
      subtitle: level.minimumThreshold > 0
          ? Text(
              'Seuil: ${level.minimumThreshold} unités',
              style: const TextStyle(fontSize: 11, color: Colors.grey),
            )
          : null,
      trailing: isLow
          ? Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.red,
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Text(
                'Stock bas',
                style: TextStyle(color: Colors.white, fontSize: 10),
              ),
            )
          : null,
    );
  }
}
