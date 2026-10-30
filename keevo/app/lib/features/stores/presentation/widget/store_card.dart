import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/sync/held_operations.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/held_operation_badge.dart';
import '../../domain/model/store_model.dart';
import '../../domain/model/store_type.dart';
import 'store_type_icon.dart';

/// StoreCard — tile representing a single store or warehouse.
///
/// Shows: name, type icon, address/phone if set.
/// Inactive stores are greyed out with a "Désactivée" badge.
/// Long-press triggers the action menu (owner only) : « Modifier » /
/// « Désactiver » sur une boutique active, « Réactiver » sur une boutique
/// désactivée (sinon impossible de la remettre en service).
///
/// Story 3.1 — Task 20.2.
class StoreCard extends ConsumerWidget {
  final StoreModel store;
  final VoidCallback? onEdit;
  final VoidCallback? onDeactivate;
  final VoidCallback? onReactivate;

  const StoreCard({
    super.key,
    required this.store,
    this.onEdit,
    this.onDeactivate,
    this.onReactivate,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final isInactive = !store.isActive;
    // Story 13.8b: held store → badge under the address; otherwise the
    // ListTile keeps its usual one/two-line layout.
    final held = ref.watch(heldEntityReasonsProvider
        .select((m) => m.valueOrNull?.containsKey(store.id) ?? false));
    final subtitle = _subtitle(theme);

    return Opacity(
      opacity: isInactive ? 0.55 : 1.0,
      child: GestureDetector(
        onLongPress: _showMenu(context),
        // Rangée de liste : surface + bordure fine plutôt qu'une élévation
        // Material par défaut.
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(AppTheme.radiusL),
            border: Border.all(
              color: theme.colorScheme.outlineVariant.withAlpha(90),
            ),
          ),
          child: ListTile(
            contentPadding:
                const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            leading: _typeAvatar(theme),
            title: Row(
              children: [
                Expanded(
                  child: Text(
                    store.name,
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                      color: isInactive
                          ? theme.colorScheme.onSurface.withAlpha(128)
                          : null,
                    ),
                  ),
                ),
                if (isInactive)
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(20),
                      color: theme.colorScheme.error.withAlpha(30),
                    ),
                    child: Text(
                      'Désactivée',
                      style: theme.textTheme.labelSmall
                          ?.copyWith(color: theme.colorScheme.error),
                    ),
                  ),
              ],
            ),
            subtitle: !held
                ? subtitle
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (subtitle != null) subtitle,
                      HeldOperationBadge(
                          entityId: store.id,
                          padding: EdgeInsets.only(
                              top: subtitle == null ? 0 : 4)),
                    ],
                  ),
            trailing: IconButton(
              icon: const Icon(Icons.more_vert_rounded),
              tooltip: 'Actions',
              onPressed: _showMenu(context),
            ),
          ),
        ),
      ),
    );
  }

  Widget _typeAvatar(ThemeData theme) {
    final isWarehouse = store.type == StoreType.warehouse;
    return CircleAvatar(
      backgroundColor: isWarehouse
          ? theme.colorScheme.tertiary.withValues(alpha: 0.12)
          : AppTheme.softTint(theme.brightness),
      child: Icon(
        storeTypeIcon(store.type),
        size: 20,
        color: isWarehouse
            ? theme.colorScheme.tertiary
            : theme.colorScheme.primary,
      ),
    );
  }

  Widget? _subtitle(ThemeData theme) {
    final parts = <String>[
      if (store.address != null && store.address!.isNotEmpty) store.address!,
      if (store.phone != null && store.phone!.isNotEmpty) store.phone!,
    ];
    if (parts.isEmpty) return null;
    return Text(
      parts.join(' · '),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: theme.textTheme.bodySmall
          ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
    );
  }

  VoidCallback _showMenu(BuildContext context) => () {
        showModalBottomSheet<void>(
          context: context,
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
          ),
          builder: (_) => SafeArea(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(height: 8),
                if (!store.isActive)
                  ListTile(
                    leading: const Icon(Icons.restart_alt_rounded),
                    title: const Text('Réactiver'),
                    onTap: () {
                      Navigator.of(context).pop();
                      onReactivate?.call();
                    },
                  ),
                if (store.isActive) ...[
                  ListTile(
                    leading: const Icon(Icons.edit_outlined),
                    title: const Text('Modifier'),
                    onTap: () {
                      Navigator.of(context).pop();
                      onEdit?.call();
                    },
                  ),
                  ListTile(
                    leading: Icon(Icons.block_rounded,
                        color: Theme.of(context).colorScheme.error),
                    title: Text(
                      'Désactiver',
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
                    onTap: () {
                      Navigator.of(context).pop();
                      onDeactivate?.call();
                    },
                  ),
                ],
                const SizedBox(height: 8),
              ],
            ),
          ),
        );
      };
}
