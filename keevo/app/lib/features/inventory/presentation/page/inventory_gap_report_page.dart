import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../../core/di/providers.dart';
import '../../domain/model/inventory_gap_report_model.dart';
import '../../domain/service/inventory_report_text_formatter.dart';
import '../provider/gap_report_provider.dart';
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
        title: const Text('Rapport d\'inventaire'),
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
        error: (e, st) => Center(
          child: Text('Erreur: $e'),
        ),
        data: (report) => _buildReportBody(context, ref, report),
      ),
    );
  }

  Widget _buildReportBody(
      BuildContext context, WidgetRef ref, InventoryGapReportModel report) {
    final theme = Theme.of(context);

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
            titleColor: const Color(0xFFFA5252),
            onRowTap: (row) => ProductDetailBottomSheet.show(context, row),
          ),
        if (report.shortageRows.isNotEmpty) const SizedBox(height: 16),

        // 4. Surplus section (AC1)
        if (report.surplusRows.isNotEmpty)
          GapSectionList(
            title: '⚠️ Surplus',
            rows: report.surplusRows,
            titleColor: const Color(0xFFFCC419),
            onRowTap: (row) => ProductDetailBottomSheet.show(context, row),
          ),
        const SizedBox(height: 24),

        // 5. "Appliquer les ajustements" placeholder (AC6)
        FilledButton.icon(
          onPressed: null, // disabled — Story 6.4
          icon: const Icon(Icons.check_circle_outline),
          label: const Text('Appliquer les ajustements'),
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
            'Disponible dans une prochaine mise à jour',
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
            textAlign: TextAlign.center,
          ),
        ),
      ],
    );
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
