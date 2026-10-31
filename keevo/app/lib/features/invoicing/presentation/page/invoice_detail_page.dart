import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../domain/model/invoice.dart';
import '../provider/invoicing_providers.dart';

/// Story 15.3 — minimal read-only invoice screen (`/invoicing/:id`, route of
/// the Invoicing module): number, status in French, client, total TTC and
/// « Voir le PDF ». Story 15.5 extends it (payment, WhatsApp, credit notes).
class InvoiceDetailPage extends ConsumerWidget {
  final String invoiceId;

  const InvoiceDetailPage({super.key, required this.invoiceId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final provider = invoiceByIdProvider(invoiceId);
    final invoice = ref.watch(provider);
    return Scaffold(
      appBar: AppBar(title: const Text('Facture')),
      body: invoice.when(
        data: (i) => _InvoiceContent(invoice: i),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(
          error: e,
          onRetry: () => ref.invalidate(provider),
        ),
      ),
    );
  }
}

class _InvoiceContent extends ConsumerStatefulWidget {
  final Invoice invoice;

  const _InvoiceContent({required this.invoice});

  @override
  ConsumerState<_InvoiceContent> createState() => _InvoiceContentState();
}

class _InvoiceContentState extends ConsumerState<_InvoiceContent> {
  static final _currency =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'XAF', decimalDigits: 0);

  bool _downloading = false;

  Future<void> _openPdf() async {
    setState(() => _downloading = true);
    try {
      await ref.read(invoicePdfOpenerProvider)(widget.invoice);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(appErrorMessage(e)),
        backgroundColor: AppTheme.errorStrong,
      ));
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  Color _statusColor(ColorScheme cs) => switch (widget.invoice.status) {
        kInvoicePaid => AppTheme.successStrong,
        kInvoiceCancelled => cs.error,
        kInvoiceDraft => cs.onSurfaceVariant,
        _ => cs.primary,
      };

  @override
  Widget build(BuildContext context) {
    final invoice = widget.invoice;
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final client = invoice.clientName;
    final statusColor = _statusColor(cs);
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          invoice.number != null
              ? 'Facture n° ${invoice.number}'
              : 'Facture (brouillon)',
          key: const Key('invoice-number'),
          style:
              theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            decoration: BoxDecoration(
              color: statusColor.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Text(
              invoice.statusLabel,
              key: const Key('invoice-status'),
              style: TextStyle(color: statusColor, fontWeight: FontWeight.w600),
            ),
          ),
        ),
        const SizedBox(height: 16),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.person_outline_rounded),
          title: const Text('Client'),
          subtitle:
              Text(client == null || client.isEmpty ? 'Non renseigné' : client),
        ),
        const Divider(),
        Row(
          children: [
            Expanded(
              child: Text('Total TTC',
                  style: theme.textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.w700)),
            ),
            Text(
              _currency.format(invoice.totalTtc),
              key: const Key('invoice-total'),
              style: theme.textTheme.titleMedium
                  ?.copyWith(fontWeight: FontWeight.w800),
            ),
          ],
        ),
        const SizedBox(height: 24),
        FilledButton.icon(
          onPressed: _downloading ? null : _openPdf,
          icon: _downloading
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.picture_as_pdf_outlined),
          label: const Text('Voir le PDF'),
        ),
      ],
    );
  }
}
