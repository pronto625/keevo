import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../stores/domain/model/store_model.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../domain/model/cross_store_availability_model.dart';
import '../provider/cross_store_availability_provider.dart';
import '../../../inventory/presentation/widget/transfer_form_bottom_sheet.dart';

/// Opens the cross-store availability bottom sheet for [productId].
///
/// Story 3.4 — entry point called from ProductCard long-press and
/// StoreProductStockTile onTap.
Future<void> showCrossStoreAvailabilitySheet({
  required BuildContext context,
  required String productId,
  required String productName,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Colors.transparent,
    builder: (_) => _CrossStoreAvailabilitySheet(
      productId: productId,
      productName: productName,
    ),
  );
}

// ── Bottom sheet ─────────────────────────────────────────────────────────────

class _CrossStoreAvailabilitySheet extends ConsumerWidget {
  final String productId;
  final String productName;

  const _CrossStoreAvailabilitySheet({
    required this.productId,
    required this.productName,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncModel =
        ref.watch(crossStoreAvailabilityProvider(productId));
    final theme = Theme.of(context);

    return Container(
      constraints: BoxConstraints(
        maxHeight: MediaQuery.of(context).size.height * 0.85,
      ),
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        boxShadow: [
          BoxShadow(
            color: theme.colorScheme.shadow.withOpacity(0.12),
            blurRadius: 20,
            offset: const Offset(0, -4),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Handle
          Container(
            width: 40,
            height: 4,
            margin: const EdgeInsets.only(top: 12, bottom: 8),
            decoration: BoxDecoration(
              color: theme.colorScheme.outline.withOpacity(0.3),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          // Header
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    Icons.store_outlined,
                    color: theme.colorScheme.onPrimaryContainer,
                    size: 22,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Disponibilité cross-boutique',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        productName,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.of(context).pop(),
                  icon: Icon(
                    Icons.close_rounded,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          // Content
          asyncModel.when(
            loading: () => const Padding(
              padding: EdgeInsets.symmetric(vertical: 48),
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                'Données locales affichées — synchronisation en attente.',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            data: (model) => _SheetBody(
              productId: productId,
              productName: productName,
              model: model,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Sheet body ────────────────────────────────────────────────────────────────

class _SheetBody extends ConsumerWidget {
  final String productId;
  final String productName;
  final CrossStoreAvailabilityModel model;

  const _SheetBody({
    required this.productId,
    required this.productName,
    required this.model,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final role = ref.watch(currentUserRoleProvider);
    final isOwner = role == 'OWNER';

    // AC1/AC4: available stores first, then by qty desc, zero-stock last.
    final sortedEntries = [...model.entries]
      ..sort((a, b) {
        if (a.quantity > 0 && b.quantity == 0) return -1;
        if (a.quantity == 0 && b.quantity > 0) return 1;
        return b.quantity.compareTo(a.quantity);
      });
    final sortedModel = model.copyWith(entries: sortedEntries);

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Store list
        ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(context).size.height * 0.45,
          ),
          child: sortedModel.entries.isEmpty
              ? const Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Aucune boutique disponible.')),
                )
              : ListView.separated(
                  shrinkWrap: true,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  itemCount: sortedModel.entries.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 4),
                  itemBuilder: (context, i) =>
                      _StoreTile(entry: sortedModel.entries[i]),
                ),
        ),
        // Footer CTA
        _SheetFooter(
          productId: productId,
          productName: productName,
          model: sortedModel,
          isOwner: isOwner,
        ),
      ],
    );
  }
}

// ── Store availability tile ───────────────────────────────────────────────────

class _StoreTile extends StatelessWidget {
  final CrossStoreAvailabilityEntry entry;

  const _StoreTile({required this.entry});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isOutOfStock = entry.stockStatus == CrossStoreStockStatus.outOfStock;
    final isLow = entry.stockStatus == CrossStoreStockStatus.low;

    final Color qtyColor = isOutOfStock
        ? theme.colorScheme.onSurfaceVariant.withOpacity(0.4)
        : isLow
            ? AppTheme.warning
            : AppTheme.success;

    final Color tileColor = isOutOfStock
        ? theme.colorScheme.surfaceVariant.withOpacity(0.3)
        : theme.colorScheme.surface;

    return Container(
      decoration: BoxDecoration(
        color: tileColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: theme.colorScheme.outline.withOpacity(0.1),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: [
            // Store type icon
            Icon(
              entry.isWarehouse
                  ? Icons.warehouse_outlined
                  : Icons.storefront_outlined,
              size: 20,
              color: isOutOfStock
                  ? theme.colorScheme.onSurfaceVariant.withOpacity(0.4)
                  : theme.colorScheme.primary,
            ),
            const SizedBox(width: 12),
            // Name
            Expanded(
              child: Text(
                entry.storeName,
                style: theme.textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w500,
                  color: isOutOfStock
                      ? theme.colorScheme.onSurfaceVariant.withOpacity(0.5)
                      : null,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            // Quantity badge — AC1: qty=0 shows '-' with 'En rupture'
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: qtyColor.withOpacity(0.1),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: qtyColor.withOpacity(0.3)),
              ),
              child: Text(
                isOutOfStock ? '-' : '${entry.quantity}',
                style: theme.textTheme.labelMedium?.copyWith(
                  color: qtyColor,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            if (isOutOfStock) ...[              const SizedBox(width: 6),
              Text(
                'En rupture',
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant.withOpacity(0.5),
                  fontStyle: FontStyle.italic,
                ),
              ),
            ],
            if (isLow) ...[
              const SizedBox(width: 6),
              Icon(Icons.warning_amber_rounded,
                  size: 16, color: AppTheme.warning),
            ],
          ],
        ),
      ),
    );
  }
}

// ── Footer CTA ────────────────────────────────────────────────────────────────

class _SheetFooter extends ConsumerWidget {
  final String productId;
  final String productName;
  final CrossStoreAvailabilityModel model;
  final bool isOwner;

  const _SheetFooter({
    required this.productId,
    required this.productName,
    required this.model,
    required this.isOwner,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Divider(height: 1),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: isOwner
              ? _OwnerCta(
                  productId: productId,
                  productName: productName,
                  model: model,
                )
              : _EmployeeCta(model: model),
        ),
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(
            'Mis à jour: ${_formatTime(model.refreshedAt)}',
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ],
    );
  }

  static String _formatTime(DateTime dt) {
    final h = dt.hour.toString().padLeft(2, '0');
    final m = dt.minute.toString().padLeft(2, '0');
    return '$h:$m';
  }
}

// ── OWNER CTA ─────────────────────────────────────────────────────────────────

class _OwnerCta extends ConsumerWidget {
  final String productId;
  final String productName;
  final CrossStoreAvailabilityModel model;

  const _OwnerCta({
    required this.productId,
    required this.productName,
    required this.model,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storeList = ref.watch(storeListNotifierProvider);
    final allStores = storeList.value ?? const [];

    // AC4: source = store with highest available stock (first in sorted list)
    final sourceEntry = model.entries.isNotEmpty ? model.entries.first : null;
    final sourceStoreId = sourceEntry?.storeId ?? '';
    final sourceStoreName = allStores
        .firstWhere(
          (s) => s.id == sourceStoreId,
          orElse: () => StoreModel(
            id: sourceStoreId,
            name: sourceEntry?.storeName ?? '',
            createdAt: DateTime.now(),
            updatedAt: DateTime.now(),
          ),
        )
        .name;

    // Destination stores = all active stores except the source
    final destinations = allStores
        .where((s) => s.isActive && s.id != sourceStoreId)
        .toList();

    // AC4: disable when all stores have qty=0
    final hasStock = model.entries.any((e) => e.quantity > 0);

    return SizedBox(
      width: double.infinity,
      child: Tooltip(
        message: hasStock ? '' : 'Aucun stock disponible dans le réseau',
        child: FilledButton.icon(
          onPressed: hasStock && destinations.isNotEmpty
              ? () {
                  Navigator.of(context).pop();
                  showTransferFormBottomSheet(
                    context: context,
                    sourceStoreId: sourceStoreId,
                    sourceStoreName: sourceStoreName,
                    productId: productId,
                    productName: productName,
                    destinationStores: destinations,
                  );
                }
              : null,
          icon: const Icon(Icons.swap_horiz_rounded),
          label: const Text('Initier un transfert'),
        ),
      ),
    );
  }
}

// ── EMPLOYEE CTA ──────────────────────────────────────────────────────────────

class _EmployeeCta extends ConsumerWidget {
  final CrossStoreAvailabilityModel model;

  const _EmployeeCta({required this.model});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);

    // AC3: get owner phone from first store's phone field
    final storeList = ref.watch(storeListNotifierProvider);
    final allStores = storeList.value ?? const [];
    final firstStore = allStores.isNotEmpty ? allStores.first : null;
    final ownerPhone = firstStore?.phone;

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          'Contacter le propriétaire pour initier un transfert',
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
          textAlign: TextAlign.center,
        ),
        // AC3: WhatsApp button shown only when owner phone is available
        if (ownerPhone != null && ownerPhone.isNotEmpty) ...[
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: () => _openWhatsApp(ownerPhone),
              icon: const Icon(Icons.chat_outlined, size: 18),
              label: const Text('Contacter via WhatsApp'),
            ),
          ),
        ],
      ],
    );
  }

  Future<void> _openWhatsApp(String phone) async {
    // AC3: deeplink to https://wa.me/{phone} — strip '+' prefix
    final cleanPhone = phone.replaceAll('+', '');
    final uri = Uri.parse('https://wa.me/$cleanPhone');
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }
}
