import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
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
    final isEmployee = ref.watch(currentUserRoleProvider) == 'EMPLOYEE';
    final userIdAsync = ref.watch(currentUserIdProvider);
    // OWNER: employeeId=null → all store sales. EMPLOYEE: scoped to own sales —
    // never query with a null id while it loads (that means the whole store).
    final employeeId = userIdAsync.valueOrNull;
    final AsyncValue<DayClosureSummary> summaryAsync = !isEmployee
        ? ref.watch(todaySummaryProvider((storeId: storeId, employeeId: null)))
        : userIdAsync.isLoading
            ? const AsyncValue.loading()
            // An employee whose id cannot be read gets an error with retry,
            // not an endless spinner (and never the store-wide figures).
            : employeeId == null
                ? AsyncValue.error(
                    userIdAsync.error ??
                        StateError('Identifiant utilisateur introuvable'),
                    userIdAsync.stackTrace ?? StackTrace.current)
                : ref.watch(todaySummaryProvider((
                    storeId: storeId,
                    employeeId: employeeId,
                  )));
    final closureStateAsync = ref.watch(dayClosureStateProvider(storeId));
    final lastClosureAsync = ref.watch(lastClosureProvider(storeId));

    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerLowest,
      body: CustomScrollView(
        slivers: [
          // ── Header ──
          SliverAppBar(
            expandedHeight: 96,
            pinned: true,
            automaticallyImplyLeading: false,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: theme.colorScheme.surface,
            flexibleSpace: FlexibleSpaceBar(
              background: ColoredBox(
                color: theme.colorScheme.surface,
                child: SafeArea(
                  bottom: false,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
                    child: Row(
                      children: [
                        const SoftIconBadge(icon: Icons.bar_chart_rounded),
                        const SizedBox(width: 12),
                        Text(
                          'Rapports',
                          style: theme.textTheme.titleLarge?.copyWith(
                            color: theme.colorScheme.onSurface,
                            fontWeight: FontWeight.w700,
                            letterSpacing: -0.3,
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
              error: (e, _) => AppErrorWidget(
                error: e,
                onRetry: () {
                  ref.invalidate(currentUserIdProvider);
                  ref.invalidate(todaySummaryProvider);
                },
              ),
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
    AsyncValue<DayClosureState> closureStateAsync,
    AsyncValue<String?> userIdAsync,
    DayClosure? lastClosure,
  ) {
    final isClosed =
        closureStateAsync.valueOrNull == DayClosureState.closed;
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
              color: theme.colorScheme.cardBg,
              borderRadius: BorderRadius.circular(AppTheme.radiusXL),
              boxShadow: AppTheme.softShadow(theme.colorScheme),
            ),
            child: Column(
              children: [
                Text(
                  'VENTES TOTAL',
                  style: TextStyle(
                    color: theme.colorScheme.onSurfaceVariant,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.2,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  _currencyFormat.format(summary.totalRevenue),
                  style: TextStyle(
                    color: theme.colorScheme.onSurface,
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
                      color: AppTheme.softTint(theme.brightness),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Text(
                      '${summary.totalSales} vente${summary.totalSales > 1 ? 's' : ''} enregistrée${summary.totalSales > 1 ? 's' : ''}',
                      style: TextStyle(
                        color: theme.colorScheme.primary,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
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
                  icon: Icons.payments_outlined,
                  label: 'ESPÈCES',
                  amount: summary.cashAmount,
                  color: theme.colorScheme.success,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _PaymentCard(
                  icon: Icons.phone_android_outlined,
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
              // Fond ambre dilué (lisible en sombre), l'icône porte l'accent.
              decoration: BoxDecoration(
                color: theme.colorScheme.warningContainer,
                borderRadius: BorderRadius.circular(AppTheme.radiusM),
                border: Border.all(
                    color: theme.colorScheme.warning.withValues(alpha: 0.4)),
              ),
              child: Row(
                children: [
                  Icon(Icons.warning_amber_rounded,
                      color: theme.brightness == Brightness.dark
                          ? theme.colorScheme.warning
                          : AppTheme.onWarning,
                      size: 20),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      isEmployee
                          ? '${summary.pendingSalesCount} vente(s) en attente de validation'
                          : '${summary.pendingSalesCount} vente(s) en attente — '
                              '${_currencyFormat.format(summary.pendingSalesTotal)}',
                      style: TextStyle(
                        color: theme.colorScheme.onSurface,
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
                side: BorderSide(color: theme.colorScheme.outlineVariant),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(AppTheme.radiusL),
                ),
              ),
              icon: Icon(Icons.assessment_outlined,
                  color: theme.colorScheme.primary, size: 20),
              label: Text(
                'Historique des rapports',
                style: TextStyle(
                  color: theme.colorScheme.primary,
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
                side: BorderSide(color: theme.colorScheme.outlineVariant),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(AppTheme.radiusL),
                ),
              ),
              icon: Icon(Icons.history_rounded,
                  color: theme.colorScheme.primary, size: 20),
              label: Text(
                'Voir l\'historique des ventes',
                style: TextStyle(
                  color: theme.colorScheme.primary,
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
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      child: Row(
        children: [
          SoftIconBadge(icon: icon, color: color, size: 36),
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
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      child: Row(
        children: [
          const SoftIconBadge(icon: Icons.inventory_2_outlined, size: 44),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 14,
                    color: cs.onSurface,
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
              color: cs.primary,
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
    final cs = Theme.of(context).colorScheme;
    return SizedBox(
      width: double.infinity,
      height: 56,
      child: ElevatedButton.icon(
        onPressed: isClosed
            ? null
            : () => _showDayCloseSummary(context, ref),
        style: ElevatedButton.styleFrom(
          backgroundColor: cs.primary,
          disabledBackgroundColor: cs.outlineVariant,
          foregroundColor: cs.onPrimary,
          disabledForegroundColor: cs.onSurfaceVariant,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(AppTheme.radiusL),
          ),
          elevation: 0,
        ),
        icon: Icon(
          isClosed ? Icons.check_circle_outline_rounded : Icons.lock_outline_rounded,
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
      employeeId: null, // closure always shows full store totals
    );

    if (!context.mounted) return;

    if (closed) {
      await DayCloseSuccessOverlay.show(context);
    }
  }
}
