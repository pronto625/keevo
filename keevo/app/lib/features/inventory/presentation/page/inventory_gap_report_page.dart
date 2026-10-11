import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_constants.dart';
import '../../domain/model/inventory_gap_report_model.dart';
import '../../domain/service/inventory_report_text_formatter.dart';
import '../provider/gap_report_provider.dart';
import '../widget/apply_adjustments_button.dart';
import '../widget/concordant_collapse_section.dart';
import '../widget/gap_report_summary_header.dart';
import '../widget/gap_section_list.dart';
import '../widget/product_detail_bottom_sheet.dart';

/// InventoryGapReportPage — main gap analysis report page.
///
/// Displays summary, concordant (collapsed), shortage & surplus sections.
/// WhatsApp share for all roles, download for OWNER only.
/// "Appliquer les ajustements" placeholder (Story 6.4).
/// Story 6.3 — AC1, AC2, AC3, AC4, AC6.
class InventoryGapReportPage extends ConsumerWidget {
  final String sessionId;

  const InventoryGapReportPage({super.key, required this.sessionId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reportAsync = ref.watch(gapReportProvider(sessionId));
    final role = ref.watch(currentUserRoleProvider);

    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        leading: Navigator.of(context).canPop()
            ? IconButton(
                icon: Icon(Icons.arrow_back_ios_new_rounded,
                    color: cs.onSurface, size: 20),
                tooltip: MaterialLocalizations.of(context).backButtonTooltip,
                onPressed: () => Navigator.maybePop(context),
              )
            : null,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Rapport d\'inventaire',
              style: TextStyle(
                fontWeight: FontWeight.w700,
                letterSpacing: -0.2,
              ),
            ),
            if (reportAsync.valueOrNull != null)
              Text(
                '${reportAsync.value!.storeName} — ${reportAsync.value!.scope}',
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
              ),
          ],
        ),
        actions: [
          // WhatsApp share button (AC4 — both OWNER and EMPLOYEE)
          SoftIconButton(
            icon: Icons.share,
            iconSize: 20,
            tooltip: 'Partager sur WhatsApp',
            onPressed: reportAsync.valueOrNull != null
                ? () => _shareWhatsApp(context, ref, reportAsync.value!)
                : null,
          ),
          // Download button (AC3 — OWNER only)
          if (role == 'OWNER') ...[
            const SizedBox(width: 6),
            SoftIconButton(
              icon: Icons.download,
              iconSize: 20,
              tooltip: 'Télécharger le rapport',
              onPressed: reportAsync.valueOrNull != null
                  ? () => _downloadReport(context, ref, reportAsync.value!)
                  : null,
            ),
          ],
          const SizedBox(width: 12),
        ],
      ),
      body: reportAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => AppErrorWidget(
          error: e,
          onRetry: () => ref.invalidate(gapReportProvider(sessionId)),
        ),
        data: (report) => _buildReportBody(context, ref, report),
      ),
      bottomNavigationBar: reportAsync.whenOrNull(
        data: (report) => _buildBottomBar(context, ref, report),
      ),
    );
  }

  Widget _buildReportBody(
      BuildContext context, WidgetRef ref, InventoryGapReportModel report) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // 1. Summary header (AC1)
        GapReportSummaryHeader(summary: report.summary),
        const SizedBox(height: 16),

        // 2. Concordant section — collapsed (AC1)
        ConcordantCollapseSection(
          count: report.summary.totalConcordant,
          rows: report.concordantRows,
        ),
        const SizedBox(height: 16),

        // 3. Shortage section — sorted by value desc (AC1)
        if (report.shortageRows.isNotEmpty)
          GapSectionList(
            title: 'Manquants',
            rows: report.shortageRows,
            titleColor: AppTheme.errorColor,
            onRowTap: (row) => ProductDetailBottomSheet.show(context, row),
          ),
        if (report.shortageRows.isNotEmpty) const SizedBox(height: 16),

        // 4. Surplus section (AC1)
        if (report.surplusRows.isNotEmpty)
          GapSectionList(
            title: 'Surplus',
            rows: report.surplusRows,
            titleColor: AppTheme.warning,
            onRowTap: (row) => ProductDetailBottomSheet.show(context, row),
          ),
      ],
    );
  }

  Widget? _buildBottomBar(
      BuildContext context, WidgetRef ref, InventoryGapReportModel report) {
    final isInProgress = report.sessionStatus == 'IN_PROGRESS';
    final hasGaps =
        report.summary.totalShortage > 0 || report.summary.totalSurplus > 0;
    final role = ref.watch(currentUserRoleProvider);

    // VALIDATED — no bar
    if (report.sessionStatus == 'VALIDATED') return null;

    // No gaps — all concordant
    if (!hasGaps && isInProgress) {
      final concordantBanner = Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: AppTheme.success.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(AppTheme.radiusM),
        ),
        child: const Row(
          children: [
            Icon(Icons.check_circle_outline_rounded, color: AppTheme.success),
            SizedBox(width: 8),
            Expanded(
              child: Text(
                'Aucun ajustement nécessaire — tous les stocks correspondent',
              ),
            ),
          ],
        ),
      );

      // OWNER can still validate (close) the session even with 0 gaps
      if (role == 'OWNER') {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                concordantBanner,
                const SizedBox(height: 8),
                ApplyAdjustmentsButton(
                  sessionId: sessionId,
                  summary: report.summary,
                ),
              ],
            ),
          ),
        );
      }

      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: concordantBanner,
        ),
      );
    }

    // IN_PROGRESS + has gaps + OWNER only → show button (AC1)
    if (isInProgress && hasGaps && role == 'OWNER') {
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: ApplyAdjustmentsButton(
            sessionId: sessionId,
            summary: report.summary,
          ),
        ),
      );
    }

    return null;
  }

  Future<void> _shareWhatsApp(BuildContext context, WidgetRef ref,
      InventoryGapReportModel report) async {
    final messenger = ScaffoldMessenger.of(context);
    final actorName = _getActorName(ref);
    final text = formatWhatsAppReport(report, actorName);
    final encoded = Uri.encodeComponent(text);
    final uri = Uri.parse('https://wa.me/?text=$encoded');
    bool opened;
    try {
      opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      opened = false;
    }
    if (!opened) {
      messenger.showSnackBar(const SnackBar(
        content: Text('Impossible d\'ouvrir WhatsApp sur cet appareil.'),
      ));
    }
  }

  void _downloadReport(
      BuildContext context, WidgetRef ref, InventoryGapReportModel report) {
    final actorName = _getActorName(ref);
    final text = formatDetailedTextReport(report, actorName);
    Share.share(text);
  }

  /// The report travels outside the app (WhatsApp, share sheet): sign it
  /// with the user's first name, never their phone number.
  String _getActorName(WidgetRef ref) {
    try {
      final firstName =
          ref.read(sharedPreferencesProvider).getString(kUserFirstNameKey);
      if (firstName != null && firstName.trim().isNotEmpty) {
        return firstName.trim();
      }
    } catch (_) {
      // Preferences unavailable — neutral label below.
    }
    return 'Utilisateur';
  }
}
