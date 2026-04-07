import 'package:flutter/material.dart';

/// Returns a localised label for [entity] with [limit].
///
/// [entity] is one of: 'stores', 'products', 'employees'.
String _entityLabel(String entity, int limit) {
  return switch (entity) {
    'stores' => '$limit boutique${limit > 1 ? 's' : ''}',
    'products' => '$limit produit${limit > 1 ? 's' : ''}',
    'employees' => '$limit employé${limit > 1 ? 's' : ''}',
    _ => '$limit élément${limit > 1 ? 's' : ''}',
  };
}

/// Shows a modal bottom sheet informing the user they have reached their plan
/// limit for [entity] and prompting them to upgrade to Premium.
///
/// [entity] — one of: 'stores', 'products', 'employees'
/// [limit]  — the maximum allowed count for the current plan
///
/// Usage:
/// ```dart
/// showPlanLimitBottomSheet(context: context, entity: 'stores', limit: 1);
/// ```
Future<void> showPlanLimitBottomSheet({
  required BuildContext context,
  required String entity,
  required int limit,
}) {
  return showModalBottomSheet<void>(
    context: context,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (ctx) => Padding(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Handle indicator
          Container(
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: Theme.of(ctx).colorScheme.outlineVariant,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 20),
          const Icon(Icons.lock_outline, size: 48),
          const SizedBox(height: 16),
          Text(
            'Vous avez atteint la limite de ${_entityLabel(entity, limit)} '
            'sur votre plan gratuit',
            textAlign: TextAlign.center,
            style: Theme.of(ctx).textTheme.bodyLarge,
          ),
          const SizedBox(height: 24),
          FilledButton.icon(
            key: const Key('plan_upgrade_cta'),
            icon: const Icon(Icons.star),
            label: const Text('Passer au plan Premium'),
            onPressed: () {
              Navigator.of(ctx).pop();
              // TODO(post-MVP): navigate to plan upgrade screen
            },
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Plus tard'),
          ),
        ],
      ),
    ),
  );
}
