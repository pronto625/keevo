import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../sync/held_operations.dart';
import '../theme/app_theme.dart';

/// Story 13.8b — badge shown next to an entity of a list while one of its
/// operations is held; nothing otherwise (or while the queue is being read).
class HeldOperationBadge extends ConsumerWidget {
  final String entityId;

  /// Space around the badge, applied only when it is shown.
  final EdgeInsetsGeometry padding;

  const HeldOperationBadge(
      {super.key, required this.entityId, this.padding = EdgeInsets.zero});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reason =
        ref.watch(heldEntityReasonsProvider).value?[entityId];
    if (reason == null) return const SizedBox.shrink();
    const color = AppTheme.warningStrong;
    final badge = Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: AppTheme.warning.withValues(alpha: 0.2),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.schedule, size: 12, color: color),
          const SizedBox(width: 4),
          Flexible(
            child: Text(
              heldOperationBadgeLabel(reason),
              style: const TextStyle(
                color: color,
                fontSize: 11,
                fontWeight: FontWeight.w600,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
    return padding == EdgeInsets.zero
        ? badge
        : Padding(padding: padding, child: badge);
  }
}
