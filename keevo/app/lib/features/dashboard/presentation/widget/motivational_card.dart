import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';

/// MotivationalCard — dismissible daily motivational message card.
///
/// Story 7.1 — AC5, UX27.
class MotivationalCard extends StatelessWidget {
  final String message;
  final VoidCallback onDismiss;

  const MotivationalCard({
    super.key,
    required this.message,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 0,
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      color: AppTheme.primary.withValues(alpha: 0.08),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('💡', style: TextStyle(fontSize: 20)),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                message,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      fontStyle: FontStyle.italic,
                    ),
              ),
            ),
            IconButton(
              icon: Icon(
                Icons.close,
                size: 18,
                color: Theme.of(context)
                    .colorScheme
                    .onSurface
                    .withValues(alpha: 0.5),
              ),
              onPressed: onDismiss,
              constraints: const BoxConstraints(minHeight: 32, minWidth: 32),
              padding: EdgeInsets.zero,
              visualDensity: VisualDensity.compact,
              tooltip: 'Fermer',
            ),
          ],
        ),
      ),
    );
  }
}
