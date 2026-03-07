import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
      AccountStatus.suspended => _Banner(
          color: Colors.red.shade700,
          icon: Icons.block_outlined,
          message:
              'Votre compte est suspendu. Contactez le support pour plus d\'informations.',
        ),
      AccountStatus.trialExpired => _Banner(
          color: Colors.amber.shade700,
          icon: Icons.access_time_outlined,
          message:
              'Votre période d\'essai est terminée. Passez au plan Premium pour continuer.',
        ),
      AccountStatus.active => const SizedBox.shrink(),
    };
  }
}

class _Banner extends StatelessWidget {
  final Color color;
  final IconData icon;
  final String message;

  const _Banner({
    required this.color,
    required this.icon,
    required this.message,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: color,
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 16),
      child: Row(
        children: [
          Icon(icon, color: Colors.white, size: 20),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(color: Colors.white, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}
