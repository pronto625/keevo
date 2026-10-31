import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo_core/di/core_providers.dart';
import '../../domain/model/export_models.dart';

/// « Exporter » app-bar button (v1s-18-4): opens the « Exporter » screen
/// pre-filled with [prefill]. Rendered for the OWNER only.
class ExportEntryButton extends ConsumerWidget {
  final ExportPrefill prefill;

  const ExportEntryButton({super.key, required this.prefill});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (ref.watch(currentUserRoleProvider) != 'OWNER') {
      return const SizedBox.shrink();
    }
    return IconButton(
      icon: Icon(Icons.ios_share_rounded,
          color: Theme.of(context).colorScheme.primary, size: 22),
      tooltip: 'Exporter',
      onPressed: () => context.push('/exports/new', extra: prefill),
    );
  }
}
