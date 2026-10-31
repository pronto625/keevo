import 'package:flutter/material.dart';

import 'package:keevo_core/theme/app_theme.dart';
import '../../domain/model/inventory_scope.dart';
import '../../domain/model/inventory_session_model.dart';

/// ActiveSessionBanner — shows the current active inventory session.
/// Story 6.1.
class ActiveSessionBanner extends StatelessWidget {
  final InventorySessionModel session;
  final VoidCallback? onResume;
  final VoidCallback? onCancel;

  const ActiveSessionBanner({
    super.key,
    required this.session,
    this.onResume,
    this.onCancel,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    // Teinte de marque pâle plutôt que primaryContainer (plus saturé),
    // cohérent avec les encarts mis en avant du rebrand.
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: AppTheme.softTint(theme.brightness),
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.inventory_2_outlined, color: cs.primary),
                const SizedBox(width: 8),
                Text(
                  'Session en cours',
                  style: theme.textTheme.titleMedium?.copyWith(
                    color: cs.onSurface,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const Spacer(),
                Chip(
                  label: Text(inventoryScopeLabel(session.scope)),
                  backgroundColor: cs.primary.withValues(alpha: 0.12),
                  side: BorderSide.none,
                  shape: const StadiumBorder(),
                  labelStyle: TextStyle(
                    color: cs.primary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Démarrée le ${_formatDate(session.startedAt)}',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: cs.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                if (onCancel != null)
                  TextButton.icon(
                    onPressed: onCancel,
                    icon: const Icon(Icons.cancel_outlined, size: 18),
                    label: const Text('Annuler'),
                    style: TextButton.styleFrom(
                      foregroundColor: theme.colorScheme.error,
                    ),
                  ),
                if (onResume != null) ...[
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    onPressed: onResume,
                    icon: const Icon(Icons.play_arrow, size: 18),
                    label: const Text('Reprendre'),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  String _formatDate(DateTime dt) {
    return '${dt.day.toString().padLeft(2, '0')}/'
        '${dt.month.toString().padLeft(2, '0')}/'
        '${dt.year} à ${dt.hour.toString().padLeft(2, '0')}:'
        '${dt.minute.toString().padLeft(2, '0')}';
  }
}
