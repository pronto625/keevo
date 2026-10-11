import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/sync/sync_monitoring_providers.dart';
import '../widget/quarantined_operations_section.dart';

/// `/sync/blocked` (all roles, v1s-16-15): the quarantined operations of this
/// device, reached from the rejection SnackBar and the sync detail sheet.
/// Employees can resend or export; the owner can also re-attach or abandon.
class BlockedOperationsPage extends ConsumerWidget {
  const BlockedOperationsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        title: Text(
          'Opérations bloquées',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(quarantinedSyncQueueProvider);
          await ref.read(quarantinedChainsProvider.future);
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: const [QuarantinedOperationsSection(standalone: true)],
        ),
      ),
    );
  }
}
