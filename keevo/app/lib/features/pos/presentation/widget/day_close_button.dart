import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../provider/day_closure_providers.dart';
import 'day_close_success_overlay.dart';
import 'day_summary_bottom_sheet.dart';

/// DayCloseButton — Button to trigger day closure.
///
/// Displays in three states based on [dayClosureStateProvider]:
/// - [DayCloseButtonState.available]: Active button with badge showing sales count
/// - [DayCloseButtonState.noSales]: Active button without badge
/// - [DayCloseButtonState.closed]: Disabled button showing "Journée clôturée ✅"
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class DayCloseButton extends ConsumerWidget {
  /// Store ID to check closure state for
  final String storeId;

  /// Actor ID (current user) for the closure
  final String actorId;

  const DayCloseButton({
    super.key,
    required this.storeId,
    required this.actorId,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stateAsync = ref.watch(dayClosureStateProvider(storeId));
    final salesCountAsync = ref.watch(todaySalesCountProvider(storeId));

    return stateAsync.when(
      data: (state) {
        switch (state) {
          case DayCloseButtonState.closed:
            return _buildClosedButton(context);
          case DayCloseButtonState.noSales:
            return _buildAvailableButton(context, ref, null);
          case DayCloseButtonState.available:
            return salesCountAsync.when(
              data: (count) => _buildAvailableButton(context, ref, count),
              loading: () => _buildAvailableButton(context, ref, null),
              error: (_, __) => _buildAvailableButton(context, ref, null),
            );
        }
      },
      loading: () => _buildLoadingButton(),
      error: (_, __) => _buildErrorButton(context, ref),
    );
  }

  /// Build button when available (active state).
  Widget _buildAvailableButton(BuildContext context, WidgetRef ref, int? count) {
    return Stack(
      clipBehavior: Clip.none,
      children: [
        ElevatedButton.icon(
          onPressed: () => _showDaySummary(context, ref),
          icon: const Icon(Icons.nightlight_round),
          label: const Text('Clôturer la journée'),
          style: ElevatedButton.styleFrom(
            backgroundColor: AppTheme.primary,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
        ),
        // Badge showing sales count
        if (count != null && count > 0)
          Positioned(
            top: -8,
            right: -8,
            child: Container(
              padding: const EdgeInsets.all(6),
              decoration: const BoxDecoration(
                color: AppTheme.errorColor,
                shape: BoxShape.circle,
              ),
              child: Text(
                count.toString(),
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
      ],
    );
  }

  /// Build button when day is already closed.
  Widget _buildClosedButton(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ElevatedButton.icon(
      onPressed: null,
      icon: const Icon(Icons.check_circle),
      label: const Text('Journée clôturée ✅'),
      style: ElevatedButton.styleFrom(
        disabledBackgroundColor: cs.outlineVariant,
        disabledForegroundColor: cs.onSurfaceVariant,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
    );
  }

  /// Build loading state button.
  Widget _buildLoadingButton() {
    return ElevatedButton(
      onPressed: null,
      style: ElevatedButton.styleFrom(
        backgroundColor: AppTheme.primary,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
      child: const SizedBox(
        width: 20,
        height: 20,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          color: Colors.white,
        ),
      ),
    );
  }

  /// Build error state button (retry).
  Widget _buildErrorButton(BuildContext context, WidgetRef ref) {
    return ElevatedButton.icon(
      onPressed: () {
        ref.invalidate(dayClosureStateProvider);
        ref.invalidate(todaySalesCountProvider);
      },
      icon: const Icon(Icons.refresh),
      label: const Text('Réessayer'),
      style: ElevatedButton.styleFrom(
        backgroundColor: AppTheme.warning,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
    );
  }

  /// Show the day summary bottom sheet.
  Future<void> _showDaySummary(BuildContext context, WidgetRef ref) async {
    final closed = await DaySummaryBottomSheet.show(
      context: context,
      ref: ref,
      storeId: storeId,
      actorId: actorId,
    );

    if (closed && context.mounted) {
      await DayCloseSuccessOverlay.show(context);
    }
  }
}
