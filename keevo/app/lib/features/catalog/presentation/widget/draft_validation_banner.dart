import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../provider/product_provider.dart';

/// DraftValidationBanner — shown in [CatalogPage] AppBar when pending drafts exist.
///
/// AC10: amber banner ("🔶 N brouillon(s) à valider") that filters the product
/// list to DRAFT-only when tapped. Styled per UX spec "Alerte attention" pattern.
class DraftValidationBanner extends ConsumerWidget {
  /// Called when the banner is tapped — should filter the product list to DRAFT-only.
  final VoidCallback onTap;

  const DraftValidationBanner({super.key, required this.onTap});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final countAsync = ref.watch(pendingDraftsCountProvider);

    return countAsync.when(
      data: (count) {
        if (count == 0) return const SizedBox.shrink();

        final theme = Theme.of(context);
        // colorScheme.tertiary = #FCC419 per KeevoTheme (amber — stock bas, brouillons)
        final warningColor = theme.colorScheme.tertiary;

        return GestureDetector(
          onTap: onTap,
          child: Container(
            width: double.infinity,
            padding:
                const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            decoration: BoxDecoration(
              color: warningColor.withOpacity(0.15),
              border: Border(
                bottom: BorderSide(
                  color: warningColor.withOpacity(0.4),
                  width: 1,
                ),
              ),
            ),
            child: Row(
              children: [
                Icon(Icons.warning_amber_rounded,
                    color: warningColor, size: 18),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '🔶 $count brouillon${count > 1 ? 's' : ''} à valider',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurface,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                Icon(Icons.chevron_right_rounded,
                    color: warningColor, size: 18),
              ],
            ),
          ),
        );
      },
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
    );
  }
}
