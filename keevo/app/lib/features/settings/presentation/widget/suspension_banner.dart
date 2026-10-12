import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_theme.dart';
import '../provider/account_status_provider.dart';

/// SuspensionBanner — displays a contextual banner based on [AccountStatus].
///
/// - [AccountStatus.suspended]: red banner — account manually suspended by admin
/// - [AccountStatus.trialExpired]: amber banner — free trial ended, now on FREE plan
/// - [AccountStatus.active]: renders nothing (SizedBox.shrink)
///
/// Place at the top of screens where account status matters (e.g., dashboard).
class SuspensionBanner extends ConsumerWidget {
  const SuspensionBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(accountStatusProvider);

    return switch (status) {
      AccountStatus.suspended => const _Banner(
          tone: _BannerTone.error,
          icon: Icons.block_outlined,
          message:
              'Votre compte est suspendu. Contactez le support pour plus d\'informations.',
        ),
      AccountStatus.trialExpired => const _Banner(
          tone: _BannerTone.warning,
          icon: Icons.access_time_outlined,
          message:
              'Vous êtes sur le plan Gratuit. Passez au plan Premium pour continuer.',
        ),
      AccountStatus.active => const SizedBox.shrink(),
    };
  }
}

enum _BannerTone { error, warning }

class _Banner extends StatelessWidget {
  final _BannerTone tone;
  final IconData icon;
  final String message;

  const _Banner({
    required this.tone,
    required this.icon,
    required this.message,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // Fond sémantique dilué + icône colorée : le texte blanc sur ambre
    // plein était peu lisible, et la teinte reste rouge/ambre.
    final (background, accent) = switch (tone) {
      // v1s-16-3 : paires conteneur / on-conteneur (AA clair et sombre).
      _BannerTone.error => (cs.errorContainer_, cs.onErrorContainer_),
      _BannerTone.warning => (cs.warningContainer, cs.onWarningContainer),
    };
    return Container(
      width: double.infinity,
      color: background,
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 16),
      child: Row(
        children: [
          Icon(icon, color: accent, size: 20),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: cs.onSurface, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}
