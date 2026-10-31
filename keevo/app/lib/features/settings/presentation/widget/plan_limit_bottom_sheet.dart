import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';

/// FREE plan limits per entity — mirror of the backend `PlanType.FREE(1, 500,
/// 3)` (stores, products, employees). v1s-16-13: the single source for the
/// app, so callers no longer pass wrong literals.
const Map<String, int> kFreePlanLimits = {
  'stores': 1,
  'products': 500,
  'employees': 3,
};

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
/// [limit]  — the maximum allowed count for the current plan; defaults to the
///            FREE value of [entity] ([kFreePlanLimits])
///
/// Usage:
/// ```dart
/// showPlanLimitBottomSheet(context: context, entity: 'stores');
/// ```
Future<void> showPlanLimitBottomSheet({
  required BuildContext context,
  required String entity,
  int? limit,
}) {
  final max = limit ?? kFreePlanLimits[entity] ?? 0;
  return showModalBottomSheet<void>(
    context: context,
    shape: const RoundedRectangleBorder(
      borderRadius:
          BorderRadius.vertical(top: Radius.circular(AppTheme.radiusXL)),
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
          const SoftIconBadge(icon: Icons.lock_outline_rounded, size: 72),
          const SizedBox(height: 16),
          Text(
            'Vous avez atteint la limite de ${_entityLabel(entity, max)} '
            'sur votre plan gratuit',
            textAlign: TextAlign.center,
            style: Theme.of(ctx).textTheme.bodyLarge,
          ),
          const SizedBox(height: 24),
          FilledButton.icon(
            key: const Key('plan_upgrade_cta'),
            icon: const Icon(Icons.star_rounded),
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

/// Offer sheet for a Premium-only feature (v1s-18-4: Excel / PDF exports,
/// server `PLAN_FEATURE_REQUIRED`). The CTA opens the subscription screen.
Future<void> showPremiumFeatureSheet({
  required BuildContext context,
  required String title,
  required String message,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius:
          BorderRadius.vertical(top: Radius.circular(AppTheme.radiusXL)),
    ),
    builder: (ctx) => SafeArea(
      child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 16, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: Theme.of(ctx).colorScheme.outlineVariant,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
              const SizedBox(height: 20),
              const SoftIconBadge(
                  icon: Icons.workspace_premium_rounded, size: 72),
              const SizedBox(height: 16),
              Text(
                title,
                textAlign: TextAlign.center,
                style: Theme.of(ctx)
                    .textTheme
                    .titleMedium
                    ?.copyWith(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),
              Text(
                message,
                textAlign: TextAlign.center,
                style: Theme.of(ctx).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(ctx).colorScheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(height: 24),
              FilledButton.icon(
                key: const Key('premium_feature_cta'),
                icon: const Icon(Icons.star_rounded),
                label: const Text('Passer au plan Premium'),
                onPressed: () {
                  Navigator.of(ctx).pop();
                  context.push('/settings/subscription');
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
      ),
    ),
  );
}
