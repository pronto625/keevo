import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/widget/app_error_widget.dart';
import 'provider/invoicing_providers.dart';

/// Story 15.3 — handler of the extension action `invoicing.open-sale-invoice`
/// (« Facturer » on the POS checkout and the sale detail): looks up the
/// sale's automatic invoice (`GET /api/v1/invoices?saleId=`) and opens it
/// (`/invoicing/:id`). While the invoice does not exist yet (sale not synced,
/// bridge pending) a sheet says « Facture en cours de création » with
/// « Réessayer ». Loaded lazily (`deferred as`) by the action registry.
Future<void> openSaleInvoice(
    BuildContext context, Map<String, Object?> args) async {
  final saleId = args['saleId'];
  if (saleId is! String || saleId.isEmpty) return;
  final invoiceId = await showModalBottomSheet<String>(
    context: context,
    showDragHandle: true,
    builder: (_) => SaleInvoiceLookupSheet(saleId: saleId),
  );
  if (invoiceId == null || !context.mounted) return;
  await context.push('/invoicing/${Uri.encodeComponent(invoiceId)}');
}

/// Looks the sale's invoice up; pops with its id once found.
class SaleInvoiceLookupSheet extends ConsumerStatefulWidget {
  final String saleId;

  const SaleInvoiceLookupSheet({super.key, required this.saleId});

  @override
  ConsumerState<SaleInvoiceLookupSheet> createState() =>
      _SaleInvoiceLookupSheetState();
}

class _SaleInvoiceLookupSheetState
    extends ConsumerState<SaleInvoiceLookupSheet> {
  bool _closed = false;

  void _close(String invoiceId) {
    if (_closed) return;
    _closed = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.of(context).pop(invoiceId);
    });
  }

  @override
  Widget build(BuildContext context) {
    final provider = invoiceForSaleProvider(widget.saleId);
    final lookup = ref.watch(provider);
    final theme = Theme.of(context);
    final Widget body;
    if (lookup.isLoading) {
      body = const Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircularProgressIndicator(),
          SizedBox(height: 16),
          Text('Recherche de la facture…'),
        ],
      );
    } else if (lookup.hasError) {
      body = AppErrorWidget(
        error: lookup.error!,
        onRetry: () => ref.invalidate(provider),
      );
    } else if (lookup.value == null) {
      body = Column(
        key: const Key('invoice-pending'),
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.hourglass_top_rounded,
              size: 40, color: theme.colorScheme.primary),
          const SizedBox(height: 12),
          Text('Facture en cours de création',
              style: theme.textTheme.titleMedium
                  ?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          Text(
            'La facture de cette vente sera disponible dès que la vente '
            'aura été synchronisée.',
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => ref.invalidate(provider),
            icon: const Icon(Icons.refresh_rounded),
            label: const Text('Réessayer'),
          ),
        ],
      );
    } else {
      _close(lookup.value!.id);
      body = const Center(child: CircularProgressIndicator());
    }
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
        child: SizedBox(width: double.infinity, child: Center(child: body)),
      ),
    );
  }
}
