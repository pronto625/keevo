import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../../core/di/providers.dart';
import '../provider/report_history_providers.dart';
import '../../domain/exception/report_exception.dart';
import '../../domain/model/report_history_model.dart';

/// ReportDetailPage — displays the full content of a single end-of-day report
/// and provides a "Renvoyer" action for OWNER users.
///
/// Story 7.2 — Task 22.
class ReportDetailPage extends ConsumerWidget {
  final String reportId;
  final ReportHistoryModel? report;

  const ReportDetailPage({
    super.key,
    required this.reportId,
    this.report,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (report != null) {
      return _DetailScaffold(reportId: reportId, report: report!);
    }
    final reportAsync = ref.watch(reportDetailProvider(reportId));
    // _DetailScaffold has its own Scaffold + AppBar: never nest it in another.
    final loaded = reportAsync.valueOrNull;
    if (loaded != null) {
      return _DetailScaffold(reportId: reportId, report: loaded);
    }
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        title: const Text('Détail du rapport'),
        titleTextStyle: _titleStyle(cs),
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      body: reportAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(error: e),
        data: (_) => const Center(child: Text('Rapport introuvable.')),
      ),
    );
  }
}

TextStyle _titleStyle(ColorScheme cs) => TextStyle(
      fontSize: 18,
      fontWeight: FontWeight.w700,
      letterSpacing: -0.2,
      color: cs.onSurface,
    );

class _DetailScaffold extends ConsumerWidget {
  final String reportId;
  final ReportHistoryModel report;

  const _DetailScaffold({required this.reportId, required this.report});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final resendState = ref.watch(resendReportNotifierProvider);

    ref.listen<AsyncValue<void>>(resendReportNotifierProvider, (prev, next) {
      if (prev is AsyncLoading && next is AsyncData) {
        ref.invalidate(reportDetailProvider(reportId));
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Rapport renvoyé avec succès via WhatsApp'),
            backgroundColor: AppTheme.success,
            behavior: SnackBarBehavior.floating,
          ),
        );
      } else if (next is AsyncError) {
        final err = next.error;
        final message = err is ReportException
            ? err.message
            : 'Échec du renvoi. Réessayez plus tard.';
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(message),
            backgroundColor: AppTheme.errorColor,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    });
    final role = ref.watch(currentUserRoleProvider);
    final isOwner = role == 'OWNER';
    final dateFormat = DateFormat("EEEE d MMMM yyyy", 'fr_FR');
    final timeFormat = DateFormat("HH:mm", 'fr_FR');

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        title: const Text('Détail du rapport'),
        titleTextStyle: _titleStyle(cs),
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
          onPressed: () => context.pop(),
        ),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // ── Status + type header ─────────────────────────────────────
            _StatusHeader(report: report),
            const SizedBox(height: 20),

            // ── Key metrics ──────────────────────────────────────────────
            Row(
              children: [
                Expanded(
                  child: _MetricCard(
                    label: 'Chiffre du jour',
                    value: _formatCurrency(report.totalRevenue),
                    icon: Icons.payments_outlined,
                    color: cs.success,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _MetricCard(
                    label: 'Ventes',
                    value: '${report.totalSales}',
                    icon: Icons.shopping_cart_outlined,
                    color: theme.colorScheme.primary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),

            // ── Delivery info ────────────────────────────────────────────
            _SoftCard(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Informations d\'envoi',
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _InfoRow(
                      label: 'Boutique',
                      value: report.storeName ?? report.storeId,
                    ),
                    _InfoRow(
                      label: 'Date du rapport',
                      value: dateFormat.format(report.reportDate),
                    ),
                    _InfoRow(
                      label: 'Généré le',
                      value:
                          '${dateFormat.format(report.createdAt)} à ${timeFormat.format(report.createdAt)}',
                    ),
                    _InfoRow(
                      label: 'Tentatives d\'envoi',
                      value: '${report.deliveryAttempts}',
                    ),
                    if (report.lastAttemptAt != null)
                      _InfoRow(
                        label: 'Dernière tentative',
                        value:
                            '${dateFormat.format(report.lastAttemptAt!)} à ${timeFormat.format(report.lastAttemptAt!)}',
                      ),
                    _InfoRow(
                      label: 'Automatique',
                      value: report.isAutomatic ? 'Oui' : 'Non',
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // ── Report content ───────────────────────────────────────────
            _SoftCard(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Contenu du rapport',
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const Divider(height: 24),
                    SelectableText(
                      report.content,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        fontFamily: 'monospace',
                        height: 1.5,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),

            // ── Resend button (OWNER only) ────────────────────────────────
            if (isOwner) ...[
              SizedBox(
                width: double.infinity,
                height: 56,
                child: ElevatedButton.icon(
                  onPressed: resendState is AsyncLoading
                      ? null
                      : () => ref
                          .read(resendReportNotifierProvider.notifier)
                          .resend(reportId),
                  icon: resendState is AsyncLoading
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.send_outlined),
                  label: const Text('Renvoyer via WhatsApp'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: cs.primary,
                    foregroundColor: cs.onPrimary,
                    elevation: 0,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(AppTheme.radiusL),
                    ),
                  ),
                ),
              ),
            ],
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  String _formatCurrency(int xaf) {
    return NumberFormat.currency(locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0)
        .format(xaf);
  }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

class _StatusHeader extends StatelessWidget {
  final ReportHistoryModel report;
  const _StatusHeader({required this.report});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _TypeBadge(reportType: report.reportType),
        const Spacer(),
        _DeliveryBadge(status: report.deliveryStatus),
      ],
    );
  }
}

class _TypeBadge extends StatelessWidget {
  final String reportType;
  const _TypeBadge({required this.reportType});

  @override
  Widget build(BuildContext context) {
    final label = switch (reportType) {
      'DAILY' => 'Journalier',
      'DAILY_COMBINED' => 'Multi-boutique',
      'WEEKLY' => 'Hebdomadaire',
      _ => reportType,
    };
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: AppTheme.softTint(theme.brightness),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: theme.colorScheme.primary,
        ),
      ),
    );
  }
}

class _DeliveryBadge extends StatelessWidget {
  final String status;
  const _DeliveryBadge({required this.status});

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      'SENT' => ('Envoyé', Theme.of(context).colorScheme.success),
      'FAILED' => ('Échec', Theme.of(context).colorScheme.error),
      'IN_APP_ONLY' => ('App uniquement', Theme.of(context).colorScheme.onSurfaceVariant),
      _ => ('En attente', Theme.of(context).colorScheme.primary),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        border: Border.all(color: color.withValues(alpha: 0.4)),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _MetricCard extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final Color color;

  const _MetricCard({
    required this.label,
    required this.value,
    required this.icon,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return _SoftCard(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SoftIconBadge(icon: icon, color: color, size: 36),
            const SizedBox(height: 10),
            Text(
              value,
              style: TextStyle(
                color: color,
                fontWeight: FontWeight.w700,
                fontSize: 18,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              label,
              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;

  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 150,
            child: Text(
              label,
              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 13),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w500,
                color: Theme.of(context).colorScheme.onSurface,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Surface de carte douce commune aux blocs de la page (remplace le `Card`
/// Material par défaut).
class _SoftCard extends StatelessWidget {
  final Widget child;
  const _SoftCard({required this.child});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      child: child,
    );
  }
}
