import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../di/core_providers.dart' show isOwnerSessionProvider;
import '../network/module_entitlement_exception.dart';
import 'app_error_widget.dart' show appErrorMessage;

/// 13.10b — the « Passer à Premium » affordance shown next to a module
/// entitlement refusal (403 `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY`).
///
/// Only the owner can open the subscription screen (owner-only route): an
/// employee reads the message without the action.

/// True when [context] can offer the upgrade action: a router is mounted and
/// the session is not an employee one. An unreadable role (no ProviderScope,
/// preferences not ready) does not hide it.
bool canOfferPremiumUpgrade(BuildContext context) {
  if (GoRouter.maybeOf(context) == null) return false;
  try {
    return ProviderScope.containerOf(context, listen: false)
        .read(isOwnerSessionProvider);
  } catch (_) {
    return true;
  }
}

/// Opens the subscription / upgrade screen. From inside a bottom sheet or a
/// dialog, closes it first (the screen must not open behind it).
void openPremiumUpgrade(BuildContext context) {
  final router = GoRouter.maybeOf(context);
  if (ModalRoute.of(context) is PopupRoute) Navigator.of(context).pop();
  router?.push(ModuleEntitlementException.upgradeRoute);
}

/// A SnackBar for [error]: its French message (`appErrorMessage`) and, for a
/// module entitlement refusal, the « Passer à Premium » action.
///
/// [context] must be mounted: after an `await`, use [appErrorSnackBarFor]
/// built before it.
SnackBar appErrorSnackBar(
  BuildContext context,
  Object error, {
  Color? backgroundColor,
  SnackBarBehavior? behavior,
  Duration duration = const Duration(seconds: 4),
}) =>
    appErrorSnackBarFor(context)(error,
        backgroundColor: backgroundColor,
        behavior: behavior,
        duration: duration);

/// Signature of the builder returned by [appErrorSnackBarFor].
typedef AppErrorSnackBarBuilder = SnackBar Function(
  Object error, {
  Color? backgroundColor,
  SnackBarBehavior? behavior,
  Duration duration,
});

/// Captures what the SnackBar needs from [context] (router, role) now, so the
/// SnackBar can be built after an `await` without touching the context again.
AppErrorSnackBarBuilder appErrorSnackBarFor(BuildContext context) {
  final router = GoRouter.maybeOf(context);
  final canUpgrade = canOfferPremiumUpgrade(context);
  return (
    Object error, {
    Color? backgroundColor,
    SnackBarBehavior? behavior,
    Duration duration = const Duration(seconds: 4),
  }) {
    final withAction =
        ModuleEntitlementException.of(error) != null && canUpgrade;
    return SnackBar(
      content: Text(appErrorMessage(error)),
      backgroundColor: backgroundColor,
      behavior: behavior,
      duration: withAction ? const Duration(seconds: 6) : duration,
      action: withAction
          ? SnackBarAction(
              label: ModuleEntitlementException.upgradeLabel,
              onPressed: () =>
                  router?.push(ModuleEntitlementException.upgradeRoute),
            )
          : null,
    );
  };
}

/// The « Passer à Premium » button (screens and inline errors).
class PremiumUpgradeButton extends StatelessWidget {
  /// Tonal filled button (full-page state) or text button (inline row).
  final bool compact;

  const PremiumUpgradeButton({super.key, this.compact = false});

  @override
  Widget build(BuildContext context) {
    if (!canOfferPremiumUpgrade(context)) return const SizedBox.shrink();
    if (compact) {
      return TextButton(
        onPressed: () => openPremiumUpgrade(context),
        child: const Text(ModuleEntitlementException.upgradeLabel),
      );
    }
    return FilledButton.icon(
      onPressed: () => openPremiumUpgrade(context),
      icon: const Icon(Icons.workspace_premium_rounded),
      label: const Text(ModuleEntitlementException.upgradeLabel),
    );
  }
}
