import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../pos/domain/model/day_closure_model.dart';
import '../../../pos/presentation/provider/day_closure_providers.dart';
import '../../../pos/presentation/widget/day_close_success_overlay.dart';
import '../../../pos/presentation/widget/day_summary_bottom_sheet.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';

/// ReportsPage — Daily sales summary + day closure.
///
/// Shows: chiffre d'affaires total, payment breakdown (espèces / mobile money),
/// top products sold, report preview card, and closure button.
class ReportsPage extends ConsumerWidget {
  const ReportsPage({super.key});

  static final _currencyFormat = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final storeId = ref.watch(activeStoreIdProvider) ?? 'default';
    final summaryAsync = ref.watch(todaySummaryProvider(storeId));
    final closureStateAsync = ref.watch(dayClosureStateProvider(storeId));
    final userIdAsync = ref.watch(currentUserIdProvider);
    final lastClosureAsync = ref.watch(lastClosureProvider(storeId));

    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerLowest,
      body: CustomScrollView(
        slivers: [
          // ── Blue gradient header ──
          SliverAppBar(
            expandedHeight: 100,
            pinned: true,
            automaticallyImplyLeading: false,
            elevation: 0,
            backgroundColor: AppTheme.primary,
            flexibleSpace: FlexibleSpaceBar(
              background: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: [AppTheme.primary, AppTheme.primary.withAlpha(180)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                ),
                child: SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
                    child: Row(
                      children: [
                        Icon(Icons.bar_chart_rounded,
                            color: Colors.white.withAlpha(230), size: 28),
                        const SizedBox(width: 12),
                        Text(
                          'Rapports',
                          style: theme.textTheme.titleLarge?.copyWith(
                            color: Colors.white,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),

          // ── Content ──
          SliverToBoxAdapter(
            child: summaryAsync.when(
              loading: () => const Padding(
                padding: EdgeInsets.all(64),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (e, _) => AppErrorWidget(error: e),
              data: (summary) => _buildContent(
                context,
                ref,
                theme,
                summary,
                storeId,
                closureStateAsync,
                userIdAsync,
                lastClosureAsync.valueOrNull,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildContent(
    BuildContext context,
    WidgetRef ref,
    ThemeData theme,
    DayClosureSummary summary,
    String storeId,
    AsyncValue<DayCloseButtonState> closureStateAsync,
    AsyncValue<String?> userIdAsync,
    DayClosure? lastClosure,
  ) {
    final isClosed =
        closureStateAsync.valueOrNull == DayCloseButtonState.closed;
    // B3.4: mask financial totals for EMPLOYEE role (AC6 / HF-2).
    final isEmployee = ref.watch(currentUserRoleProvider) == 'EMPLOYEE';

    // Compute period label: "depuis HH:mm" for same-day closure,
    // "depuis le dd/MM" for an older closure, or "depuis le début de la journée".
    final now = DateTime.now();
    final startOfToday = DateTime(now.year, now.month, now.day);
    final String periodLabel;
    if (lastClosure == null) {
      periodLabel = "depuis le début de la journée";
    } else if (lastClosure.closedAt.isAfter(startOfToday)) {
      final hm = DateFormat('HH:mm', 'fr_FR').format(lastClosure.closedAt);
      periodLabel = "depuis la clôture de $hm";
    } else {
      final dateShort = DateFormat('d MMM', 'fr_FR').format(lastClosure.closedAt);
      periodLabel = "depuis le $dateShort";
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 20),

          // ── Header text ──
          Text(
            'Résumé de votre journée',
            style: theme.textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            summary.hasSales
                ? 'Excellent travail — $periodLabel'
                : 'Aucune vente enregistrée pour le moment.',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 20),

          // ── Total revenue card ──
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [AppTheme.primary, AppTheme.primary.withAlpha(200)],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(20),
              boxShadow: [
                BoxShadow(
                  color: AppTheme.primary.withValues(alpha: 0.3),
                  blurRadius: 16,
                  offset: const Offset(0, 6),
                ),
              ],
            ),
            child: Column(
              children: [
                Text(
                  'VENTES TOTAL',
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.8),
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 1.2,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _currencyFormat.format(summary.totalRevenue),
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 32,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                if (summary.totalSales > 0) ...[
                  const SizedBox(height: 6),
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      '${summary.totalSales} vente${summary.totalSales > 1 ? 's' : ''} enregistrée${summary.totalSales > 1 ? 's' : ''}',
                      style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.9),
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),

          // ── Payment breakdown ──
          Row(
            children: [
              Expanded(
                child: _PaymentCard(
                  icon: Icons.payments_rounded,
                  label: 'ESPÈCES',
                  amount: summary.cashAmount,
                  color: AppTheme.success,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _PaymentCard(
                  icon: Icons.phone_android_rounded,
                  label: 'MOBILE MONEY',
                  amount: summary.momoAmount,
                  color: theme.colorScheme.primary,
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),

          // ── Top Products ──
          if (summary.topProductName != null) ...[
            Text(
              'Top Produits Vendus',
              style: theme.textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.w600,
                color: theme.colorScheme.onSurface,
              ),
            ),
            const SizedBox(height: 12),
            _TopProductTile(
              name: summary.topProductName!,
              quantity: summary.topProductQty,
              amount: summary.topProductRevenue,
            ),
            const SizedBox(height: 24),
          ],

          // ── Pending sales warning ──
          if (summary.hasPendingSales)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.only(bottom: 16),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: AppTheme.warning,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppTheme.onWarning.withOpacity(0.2)),
              ),
              child: Row(
                children: [
                  const Icon(Icons.warning_rounded,
                      color: AppTheme.onWarning, size: 20),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      isEmployee
                          ? '${summary.pendingSalesCount} vente(s) en attente de validation'
                          : '${summary.pendingSalesCount} vente(s) en attente — '
                              '${_currencyFormat.format(summary.pendingSalesTotal)}',
                      style: const TextStyle(
                        color: AppTheme.onWarning,
                        fontWeight: FontWeight.w500,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ],
              ),
            ),

          // ── Report history link — Story 7.2 ──
          Container(
            width: double.infinity,
            margin: const EdgeInsets.only(bottom: 12),
            child: OutlinedButton.icon(
              onPressed: () => context.push('/reports/history'),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                side: BorderSide(color: AppTheme.primary.withValues(alpha: 0.4)),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
              icon: Icon(Icons.assessment_outlined,
                  color: AppTheme.primary, size: 20),
              label: Text(
                'Historique des rapports',
                style: TextStyle(
                  color: AppTheme.primary,
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
            ),
          ),

          // ── Sales history link ──
          Container(
            width: double.infinity,
            margin: const EdgeInsets.only(bottom: 16),
            child: OutlinedButton.icon(
              onPressed: () => context.push('/pos/sales-history'),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                side: BorderSide(color: AppTheme.primary.withValues(alpha: 0.4)),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
              icon: Icon(Icons.history_rounded,
                  color: AppTheme.primary, size: 20),
              label: Text(
                'Voir l\'historique des ventes',
                style: TextStyle(
                  color: AppTheme.primary,
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
            ),
          ),

          // ── Closure button ──
          userIdAsync.when(
            data: (userId) {
              if (userId == null) return const SizedBox.shrink();
              return _ClosureButton(
                isClosed: isClosed,
                storeId: storeId,
                actorId: userId,
              );
            },
            loading: () => const SizedBox.shrink(),
            error: (_, __) => const SizedBox.shrink(),
          ),

          const SizedBox(height: 32),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-widgets

class _PaymentCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final int amount;
  final Color color;

  const _PaymentCard({
    required this.icon,
    required this.label,
    required this.amount,
    required this.color,
  });

  static final _fmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Theme.of(context).colorScheme.shadow.withValues(alpha: 0.04),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, color: color, size: 20),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.w600,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    letterSpacing: 0.5,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  _fmt.format(amount),
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 14,
                    color: color,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _TopProductTile extends StatelessWidget {
  final String name;
  final int quantity;
  final int amount;

  const _TopProductTile({
    required this.name,
    required this.quantity,
    required this.amount,
  });

  static final _fmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(
            color: Theme.of(context).colorScheme.shadow.withValues(alpha: 0.04),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(Icons.inventory_2_rounded,
                color: Theme.of(context).colorScheme.outline, size: 22),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 14,
                  ),
                ),
                Text(
                  '$quantity unités vendues',
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          Text(
            _fmt.format(amount),
            style: TextStyle(
              fontWeight: FontWeight.w700,
              fontSize: 14,
              color: AppTheme.primary,
            ),
          ),
        ],
      ),
    );
  }
}

class _ClosureButton extends ConsumerWidget {
  final bool isClosed;
  final String storeId;
  final String actorId;

  const _ClosureButton({
    required this.isClosed,
    required this.storeId,
    required this.actorId,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SizedBox(
      width: double.infinity,
      child: ElevatedButton.icon(
        onPressed: isClosed
            ? null
            : () => _showDayCloseSummary(context, ref),
        style: ElevatedButton.styleFrom(
          backgroundColor: AppTheme.primary,
          disabledBackgroundColor: Theme.of(context).colorScheme.outlineVariant,
          foregroundColor: Colors.white,
          disabledForegroundColor: Theme.of(context).colorScheme.onSurfaceVariant,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          elevation: isClosed ? 0 : 4,
          shadowColor: AppTheme.primary.withValues(alpha: 0.4),
        ),
        icon: Icon(
          isClosed ? Icons.check_circle_rounded : Icons.lock_rounded,
          size: 20,
        ),
        label: Text(
          isClosed ? 'Journée clôturée' : 'Clôturer la journée',
          style: const TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }

  Future<void> _showDayCloseSummary(
      BuildContext context, WidgetRef ref) async {
    final closed = await DaySummaryBottomSheet.show(
      context: context,
      ref: ref,
      storeId: storeId,
      actorId: actorId,
    );

    if (!context.mounted) return;

    if (closed) {
      await DayCloseSuccessOverlay.show(context);
    }
  }
}
