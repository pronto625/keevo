import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../domain/model/day_closure_model.dart';
import '../provider/day_closure_providers.dart';

/// DaySummaryBottomSheet — Displays summary before day closure.
///
/// Auto-dismisses after 5 seconds with a progress indicator.
/// Shows: total sales, revenue, top product, payment breakdown, pending sales.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class DaySummaryBottomSheet extends ConsumerStatefulWidget {
  final String storeId;
  final String actorId;
  final String? employeeId;

  const DaySummaryBottomSheet({
    super.key,
    required this.storeId,
    required this.actorId,
    this.employeeId,
  });

  /// Shows the bottom sheet and handles auto-dismiss.
  /// Shows the bottom sheet. Returns `true` if the day was successfully closed.
  static Future<bool> show({
    required BuildContext context,
    required WidgetRef ref,
    required String storeId,
    required String actorId,
    String? employeeId,
  }) async {
    final result = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      isDismissible: true,
      // The sheet draws its own handle on its rounded container.
      showDragHandle: false,
      backgroundColor: Colors.transparent,
      builder: (context) => DaySummaryBottomSheet(
        storeId: storeId,
        actorId: actorId,
        employeeId: employeeId,
      ),
    );
    return result == true;
  }

  @override
  ConsumerState<DaySummaryBottomSheet> createState() => _DaySummaryBottomSheetState();
}

class _DaySummaryBottomSheetState extends ConsumerState<DaySummaryBottomSheet>
    with SingleTickerProviderStateMixin {
  late final AnimationController _progressController;
  Timer? _autoDismissTimer;
  bool _isClosing = false;
  String? _errorMessage;

  static const _autoDismissDuration = Duration(seconds: 5);

  final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  void initState() {
    super.initState();
    _progressController = AnimationController(
      vsync: this,
      duration: _autoDismissDuration,
    );
    _startAutoDismiss();
  }

  @override
  void dispose() {
    _autoDismissTimer?.cancel();
    _progressController.dispose();
    super.dispose();
  }

  void _startAutoDismiss() {
    _progressController.forward();
    _autoDismissTimer = Timer(_autoDismissDuration, () {
      if (mounted && !_isClosing) {
        Navigator.of(context).pop();
      }
    });
  }

  void _cancelAutoDismiss() {
    _autoDismissTimer?.cancel();
    _progressController.stop();
  }

  Future<void> _confirmClosure() async {
    _cancelAutoDismiss();
    setState(() => _isClosing = true);

    debugPrint('[DayClosure] _confirmClosure called for store=${widget.storeId} actor=${widget.actorId}');

    try {
      await ref.read(closeDayNotifierProvider.notifier).closeDay(
            widget.storeId,
            widget.actorId,
          );

      debugPrint('[DayClosure] closeDay succeeded');

      if (!mounted) return;

      // Pop this sheet, returning true = closure succeeded
      Navigator.of(context).pop(true);

    } catch (e, st) {
      debugPrint('[DayClosure] closeDay FAILED: $e');
      debugPrint('[DayClosure] stackTrace: $st');

      if (!mounted) return;

      setState(() {
        _isClosing = false;
        _errorMessage = appErrorMessage(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final summaryAsync = ref.watch(todaySummaryProvider((
      storeId: widget.storeId,
      employeeId: widget.employeeId,
    )));
    final cs = Theme.of(context).colorScheme;

    return Container(
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: const BorderRadius.vertical(
            top: Radius.circular(AppTheme.radiusXL)),
      ),
      padding: const EdgeInsets.all(20),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Handle bar
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: cs.outlineVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Title
          Row(
            children: [
              Icon(Icons.nightlight_round, color: cs.primary),
              const SizedBox(width: 8),
              Text(
                'Résumé de la journée',
                style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.2,
                  color: cs.onSurface,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),

          // Progress indicator
          AnimatedBuilder(
            animation: _progressController,
            builder: (context, _) {
              return LinearProgressIndicator(
                value: 1 - _progressController.value,
                backgroundColor: cs.outlineVariant,
                valueColor: AlwaysStoppedAnimation(cs.primary),
              );
            },
          ),
          const SizedBox(height: 16),

          // Summary content
          summaryAsync.when(
            data: (summary) => _buildSummaryContent(summary),
            loading: () => const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: CircularProgressIndicator(),
              ),
            ),
            error: (e, _) => AppErrorWidget(
              error: e,
              onRetry: () {
                _cancelAutoDismiss();
                ref.invalidate(todaySummaryProvider((
                  storeId: widget.storeId,
                  employeeId: widget.employeeId,
                )));
              },
            ),
          ),

          const SizedBox(height: 20),

          // Error message (shown inline)
          if (_errorMessage != null)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.only(bottom: 12),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.errorContainer_,
                borderRadius: BorderRadius.circular(AppTheme.radiusM),
                border: Border.all(
                    color: Theme.of(context)
                        .colorScheme
                        .error
                        .withValues(alpha: 0.4)),
              ),
              child: Row(
                children: [
                  Icon(Icons.error_outline,
                      color: Theme.of(context).colorScheme.error),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _errorMessage!,
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                          fontWeight: FontWeight.w500),
                    ),
                  ),
                ],
              ),
            ),

          // Action buttons
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: _isClosing ? null : () => Navigator.of(context).pop(),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Text('Annuler'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                flex: 2,
                child: ElevatedButton(
                  onPressed: _isClosing ? null : _confirmClosure,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.primary,
                    foregroundColor: Theme.of(context).colorScheme.onPrimary,
                    elevation: 0,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: _isClosing
                      ? SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Theme.of(context).colorScheme.onPrimary,
                          ),
                        )
                      : const Text('Confirmer la clôture'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
        ],
      ),
    );
  }

  Widget _buildSummaryContent(DayClosureSummary summary) {
    return Column(
      children: [
        // Total sales and revenue
        _buildSummaryRow(
          icon: Icons.receipt_long_outlined,
          label: 'Ventes du jour',
          value: '${summary.totalSales}',
          subValue: _currencyFormat.format(summary.totalRevenue),
        ),
        const Divider(height: 24),

        // Top product
        if (summary.topProductName != null) ...[
          _buildSummaryRow(
            icon: Icons.star_outline_rounded,
            label: 'Top produit',
            value: summary.topProductName!,
            subValue: '×${summary.topProductQty}',
          ),
          const Divider(height: 24),
        ],

        // Payment breakdown
        _buildPaymentBreakdown(summary),

        // Pending sales warning
        if (summary.hasPendingSales) ...[
          const Divider(height: 24),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.warningContainer,
              borderRadius: BorderRadius.circular(AppTheme.radiusM),
              border: Border.all(color: _onWarningBox.withValues(alpha: 0.2)),
            ),
            child: Row(
              children: [
                Icon(Icons.warning_rounded, color: _onWarningBox),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '${summary.pendingSalesCount} vente(s) en attente — '
                    '${_currencyFormat.format(summary.pendingSalesTotal)} (non comptabilisé)',
                    style: TextStyle(
                      color: _onWarningBox,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }

  /// Texte sur `warningContainer` : ambre foncé en clair, ambre vif en sombre
  /// (onWarning est illisible sur le conteneur sombre).
  Color get _onWarningBox {
    final cs = Theme.of(context).colorScheme;
    return cs.brightness == Brightness.dark ? cs.warning : AppTheme.onWarning;
  }

  Widget _buildSummaryRow({
    required IconData icon,
    required String label,
    required String value,
    String? subValue,
  }) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      children: [
        Icon(icon, color: cs.onSurfaceVariant, size: 24),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            label,
            style: TextStyle(
              color: cs.onSurface,
              fontSize: 14,
            ),
          ),
        ),
        Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              value,
              style: const TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 16,
              ),
            ),
            if (subValue != null)
              Text(
                subValue,
                style: TextStyle(
                  color: cs.onSurfaceVariant,
                  fontSize: 13,
                ),
              ),
          ],
        ),
      ],
    );
  }

  Widget _buildPaymentBreakdown(DayClosureSummary summary) {
    return Row(
      children: [
        // Cash
        Expanded(
          child: Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.successContainer,
              borderRadius: BorderRadius.circular(AppTheme.radiusM),
            ),
            child: Row(
              children: [
                Icon(Icons.payments_outlined,
                    color: Theme.of(context).colorScheme.success),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Cash', style: TextStyle(fontSize: 12)),
                      Text(
                        _currencyFormat.format(summary.cashAmount),
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          color: Theme.of(context).colorScheme.success,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(width: 12),
        // MoMo
        Expanded(
          child: Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: AppTheme.softTint(Theme.of(context).brightness),
              borderRadius: BorderRadius.circular(AppTheme.radiusM),
            ),
            child: Row(
              children: [
                Icon(Icons.phone_android_rounded,
                    color: Theme.of(context).colorScheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('MoMo', style: TextStyle(fontSize: 12)),
                      Text(
                        _currencyFormat.format(summary.momoAmount),
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          color: Theme.of(context).colorScheme.primary,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
