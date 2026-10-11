import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/stock_level_model.dart';
import '../provider/stock_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
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
    final primaryStoreAsync = ref.watch(primaryStoreIdProvider);
    final activeStoreId = ref.watch(activeStoreIdProvider);
    final storesAsync = ref.watch(storeListNotifierProvider);
    final stores = storesAsync.valueOrNull ?? [];

    // Helper: resolve store name from id, fallback to truncated id.
    String storeName(String id) {
      return stores.where((s) => s.id == id).firstOrNull?.name
          ?? (id.length > 8 ? '${id.substring(0, 8)}…' : id);
    }

    // Resolve the effective storeId:
    // 1. Explicit filter param (set by parent showing a specific store tile)
    // 2. Active store selected in Settings (persisted preference)
    // 3. First level already loaded in local state (fastest path — no network wait)
    // 4. Primary store from GET /api/v1/tenant/stores (async fallback for new products)
    final effectiveStoreId = storeId
        ?? activeStoreId
        ?? stockState.levels.firstOrNull?.storeId
        ?? primaryStoreAsync.value;

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
                  ?.copyWith(fontWeight: FontWeight.w700),
            ),
            Row(
              children: [
                // Stock entry button
                SoftIconButton(
                  icon: Icons.add_box_outlined,
                  iconSize: 20,
                  tooltip: 'Entrée de stock',
                  onPressed: () => StockEntryBottomSheet.show(
                    context,
                    ref,
                    productId: productId,
                    storeId: effectiveStoreId,
                  ),
                ),
                const SizedBox(width: 6),
                // Adjust button
                SoftIconButton(
                  icon: Icons.edit_note_rounded,
                  iconSize: 20,
                  tooltip: 'Ajuster le stock',
                  onPressed: () => StockAdjustBottomSheet.show(
                    context,
                    ref,
                    productId: productId,
                    storeId: effectiveStoreId,
                  ),
                ),
                const SizedBox(width: 6),
                // Threshold config
                SoftIconButton(
                  icon: Icons.notifications_outlined,
                  iconSize: 20,
                  tooltip: 'Seuil d\'alerte',
                  onPressed: () => StockThresholdBottomSheet.show(
                    context,
                    ref,
                    productId: productId,
                  ),
                ),
                // History
                if (onViewHistory != null) ...[
                  const SizedBox(width: 6),
                  SoftIconButton(
                    icon: Icons.history_rounded,
                    iconSize: 20,
                    tooltip: 'Historique des mouvements',
                    onPressed: onViewHistory,
                  ),
                ],
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
          AppErrorWidget(
            error: stockState.error!,
            title: 'Stock indisponible',
            onRetry: () => ref.invalidate(stockNotifierProvider(productId)),
          )
        else if (stockState.levels.isEmpty)
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text('Aucun stock enregistré', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
          )
        else
          ...stockState.levels
              .where((l) {
                // If an explicit store filter is provided, apply it.
                if (storeId != null) return l.storeId == storeId;
                // If an active store is set in Settings, show only that store.
                if (activeStoreId != null) return l.storeId == activeStoreId;
                return true;
              })
              .map((level) => _StockLevelTile(
                    level: level,
                    storeName: storeName(level.storeId),
                  )),
      ],
    );
  }
}

/// Single store row in the stock level widget.
class _StockLevelTile extends StatelessWidget {
  final StockLevelModel level;
  final String storeName;

  const _StockLevelTile({required this.level, required this.storeName});

  @override
  Widget build(BuildContext context) {
    final isLow = level.isLow;
    final cs = Theme.of(context).colorScheme;
    final accent = isLow ? AppTheme.errorColor : cs.success;
    // Le vert lime manque de contraste en texte sur fond clair : on garde la
    // pastille diluée mais un vert foncé pour le chiffre en mode clair.
    final qtyColor = isLow
        ? AppTheme.errorColor
        : cs.brightness == Brightness.dark
            ? cs.success
            : AppTheme.onSuccess;

    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        radius: 20,
        backgroundColor: accent.withValues(alpha: 0.15),
        child: Text(
          '${level.quantity}',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: qtyColor,
            fontSize: 13,
          ),
        ),
      ),
      title: Text(
        storeName,
        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
      ),
      subtitle: level.minimumThreshold > 0
          ? Text(
              'Seuil: ${level.minimumThreshold} unités',
              style: TextStyle(fontSize: 11, color: Theme.of(context).colorScheme.onSurfaceVariant),
            )
          : null,
      trailing: isLow
          ? Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: AppTheme.errorColor.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(20),
              ),
              child: const Text(
                'Stock bas',
                style: TextStyle(
                  color: AppTheme.errorColor,
                  fontSize: 10,
                  fontWeight: FontWeight.w600,
                ),
              ),
            )
          : null,
    );
  }
}
