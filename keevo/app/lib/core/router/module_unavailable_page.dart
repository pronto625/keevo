import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/di/core_providers.dart' show isOwnerSessionProvider;
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/widget/entitlement_upgrade.dart';

import '../entitlements/entitlements_provider.dart';

/// Story 15.2 — « module non activé »: where the route guard sends a route of
/// a module the tenant cannot read (direct navigation or deep link).
///
/// Names the module in French (display name sent by the server with the
/// module states, never its id). The owner gets « Passer à Premium »
/// (subscription screen); an employee is told to ask the owner.
class ModuleUnavailablePage extends ConsumerWidget {
  /// Module id from the route (`?module=`), never displayed.
  final String? moduleId;

  const ModuleUnavailablePage({super.key, this.moduleId});

  static const title = 'Module non activé';
  static const employeeMessage =
      'Demandez au propriétaire d\'activer ce module.';
  static const homeLabel = 'Retour à l\'accueil';

  /// Wording when the server never sent the module name (older snapshot).
  static const fallbackMessage =
      'Cette fonctionnalité n\'est pas activée pour votre commerce.';

  /// « Le module « X » n'est pas activé… » — « le module » carries the
  /// agreement, whatever the gender / number of the name.
  static String message(String? name) => name == null
      ? fallbackMessage
      : 'Le module « $name » n\'est pas activé pour votre commerce.';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final isOwner = ref.watch(isOwnerSessionProvider);
    final entitlements =
        ref.watch(entitlementsProvider).valueOrNull ?? Entitlements.unknown;
    final id = moduleId;
    final name = id == null ? null : entitlements.displayNameOf(id);
    final home = isOwner ? '/dashboard' : '/pos';

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        automaticallyImplyLeading: false,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
          tooltip: MaterialLocalizations.of(context).backButtonTooltip,
          onPressed: () {
            if (context.canPop()) {
              context.pop();
            } else {
              context.go(home);
            }
          },
        ),
      ),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.lock_outline_rounded, size: 64, color: cs.outline),
                const SizedBox(height: 16),
                Text(
                  title,
                  key: const Key('module-unavailable-title'),
                  textAlign: TextAlign.center,
                  style: theme.textTheme.titleLarge
                      ?.copyWith(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 8),
                Text(
                  message(name),
                  key: const Key('module-unavailable-message'),
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium
                      ?.copyWith(color: cs.onSurfaceVariant),
                ),
                const SizedBox(height: 24),
                if (isOwner)
                  const PremiumUpgradeButton()
                else
                  Text(
                    employeeMessage,
                    key: const Key('module-unavailable-employee'),
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium,
                  ),
                const SizedBox(height: 12),
                TextButton(
                  key: const Key('module-unavailable-home'),
                  onPressed: () => context.go(home),
                  child: const Text(homeLabel),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
