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
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
      decoration: BoxDecoration(
        color: AppTheme.primarySoftTint,
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surface,
              shape: BoxShape.circle,
            ),
            alignment: Alignment.center,
            child: Icon(
              Icons.lightbulb_outline_rounded,
              size: 16,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
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
    );
  }
}
