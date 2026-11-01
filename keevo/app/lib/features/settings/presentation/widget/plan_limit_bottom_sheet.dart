import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo/core/network/plan_limit_error.dart';
import 'package:keevo_core/theme/app_theme.dart';
import 'package:keevo_core/widget/soft_icon_badge.dart';

import '../../domain/model/subscription_info.dart';
import '../provider/subscription_info_provider.dart';

export 'package:keevo/core/network/plan_limit_error.dart';

/// 16.8 — the limit of [entity] in the subscription info (GET
/// /subscription/me), `null` when unknown or unlimited.
int? subscriptionPlanLimit(SubscriptionInfo? info, String entity) {
  if (info == null) return null;
  return switch (entity) {
    'stores' => info.maxStores,
    'products' => info.maxProducts,
    'employees' => info.maxEmployees,
    _ => null,
  };
}

/// 16.8 — the limit to show for a refused creation: the server's number in
/// [error], else the subscription info when it is already loaded (never
/// fetched from here), else `null`.
int? knownPlanLimit(WidgetRef ref, Object? error, String entity) {
  final fromError = planLimitFromError(error);
  if (fromError != null) return fromError;
  if (!ref.exists(subscriptionInfoProvider)) return null;
  return subscriptionPlanLimit(
    ref.read(subscriptionInfoProvider).value,
    entity,
  );
}

/// 16.8 — the plan type to word the sheet for: the server's `details.plan`
/// in [error], else the subscription info when it is already loaded, else
/// `null`.
String? knownPlanType(WidgetRef ref, Object? error) {
  final fromError = planTypeFromError(error);
  if (fromError != null) return fromError;
  if (!ref.exists(subscriptionInfoProvider)) return null;
  return ref.read(subscriptionInfoProvider).value?.planType;
}

/// Whether the upgrade offer fits the plan: Free and trial tenants (or an
/// unknown plan — older servers send none); a Premium tenant already has the
/// top offer, its finite limits are raised through support.
bool planLimitOffersUpgrade(String? planType) =>
    planType == null || planType == 'FREE' || planType == 'PREMIUM_TRIAL';

/// The sentence of the sheet: with the server's number when known, else
/// without any number.
String planLimitMessage(String entity, int? limit) {
  if (limit == null || limit <= 0) {
    final what = switch (entity) {
      'stores' => 'de boutiques',
      'products' => 'de produits',
      'employees' => "d'employés",
      _ => "d'éléments",
    };
    return 'Vous avez atteint le nombre maximal $what de votre forfait';
  }
  return 'Vous avez atteint la limite de ${_entityLabel(entity, limit)} '
      'de votre forfait';
}

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
/// [limit]  — the limit the server reported ([planLimitFromError], or
///            [subscriptionPlanLimit]); `null` → a sentence without a number
///            (16.8: limits are configurable server-side, never guessed here)
/// [planType] — the tenant's plan ([knownPlanType]): the « Passer au plan
///            Premium » offer only for Free / trial (or unknown) plans, a
///            support wording otherwise (nothing is opened)
///
/// Usage:
/// ```dart
/// showPlanLimitBottomSheet(
///     context: context, entity: 'stores', limit: planLimitFromError(e));
/// ```
Future<void> showPlanLimitBottomSheet({
  required BuildContext context,
  required String entity,
  int? limit,
  String? planType,
}) {
  final offerUpgrade = planLimitOffersUpgrade(planType);
  return showModalBottomSheet<void>(
    context: context,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(
        top: Radius.circular(AppTheme.radiusXL),
      ),
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
            planLimitMessage(entity, limit),
            textAlign: TextAlign.center,
            style: Theme.of(ctx).textTheme.bodyLarge,
          ),
          const SizedBox(height: 24),
          if (offerUpgrade)
            FilledButton.icon(
              key: const Key('plan_upgrade_cta'),
              icon: const Icon(Icons.star_rounded),
              label: const Text('Passer au plan Premium'),
              onPressed: () {
                Navigator.of(ctx).pop();
                // TODO(post-MVP): navigate to plan upgrade screen
              },
            )
          else
            Text(
              'Contactez le support Keevo pour augmenter cette limite.',
              key: const Key('plan_limit_support'),
              textAlign: TextAlign.center,
              style: Theme.of(ctx).textTheme.bodyMedium?.copyWith(
                color: Theme.of(ctx).colorScheme.onSurfaceVariant,
              ),
            ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: Text(offerUpgrade ? 'Plus tard' : 'Compris'),
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
      borderRadius: BorderRadius.vertical(
        top: Radius.circular(AppTheme.radiusXL),
      ),
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
                icon: Icons.workspace_premium_rounded,
                size: 72,
              ),
              const SizedBox(height: 16),
              Text(
                title,
                textAlign: TextAlign.center,
                style: Theme.of(
                  ctx,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
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
