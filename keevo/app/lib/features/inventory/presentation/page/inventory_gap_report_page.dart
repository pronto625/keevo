import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../../core/di/providers.dart';
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

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Rapport d\'inventaire'),
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
          IconButton(
            icon: const Icon(Icons.share),
            tooltip: 'Partager sur WhatsApp',
            onPressed: reportAsync.valueOrNull != null
                ? () => _shareWhatsApp(context, ref, reportAsync.value!)
                : null,
          ),
          // Download button (AC3 — OWNER only)
          if (role == 'OWNER')
            IconButton(
              icon: const Icon(Icons.download),
              tooltip: 'Télécharger le rapport',
              onPressed: reportAsync.valueOrNull != null
                  ? () => _downloadReport(context, ref, reportAsync.value!)
                  : null,
            ),
        ],
      ),
      body: reportAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => AppErrorWidget(error: e),
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
            title: '🔴 Manquants',
            rows: report.shortageRows,
            titleColor: AppTheme.errorColor,
            onRowTap: (row) => ProductDetailBottomSheet.show(context, row),
          ),
        if (report.shortageRows.isNotEmpty) const SizedBox(height: 16),

        // 4. Surplus section (AC1)
        if (report.surplusRows.isNotEmpty)
          GapSectionList(
            title: '⚠️ Surplus',
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

    // VALIDATED — no bar
    if (report.sessionStatus == 'VALIDATED') return null;

    // No gaps — all concordant
    if (!hasGaps && isInProgress) {
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: const Color(0xFF40C057).withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Row(
              children: [
                Icon(Icons.check_circle, color: Color(0xFF40C057)),
                SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Aucun ajustement nécessaire — tous les stocks correspondent',
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    }

    // IN_PROGRESS + has gaps + OWNER only → show button (AC1)
    final role = ref.watch(currentUserRoleProvider);
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

  void _shareWhatsApp(
      BuildContext context, WidgetRef ref, InventoryGapReportModel report) {
    final actorName = _getActorName(ref);
    final text = formatWhatsAppReport(report, actorName);
    final encoded = Uri.encodeComponent(text);
    final uri = Uri.parse('https://wa.me/?text=$encoded');
    launchUrl(uri, mode: LaunchMode.externalApplication);
  }

  void _downloadReport(
      BuildContext context, WidgetRef ref, InventoryGapReportModel report) {
    final actorName = _getActorName(ref);
    final text = formatDetailedTextReport(report, actorName);
    Share.share(text);
  }

  String _getActorName(WidgetRef ref) {
    final phone = ref.read(currentUserPhoneProvider);
    return phone ?? 'Utilisateur';
  }
}
