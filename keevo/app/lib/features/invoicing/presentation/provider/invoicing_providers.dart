import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../export/presentation/provider/export_providers.dart';
import '../../data/remote_invoice_datasource.dart';
import '../../domain/model/invoice.dart';

/// HTTP adapter of the invoices API.
final invoiceDataSourceProvider = Provider<RemoteInvoiceDataSource>(
    (ref) => RemoteInvoiceDataSource(dio: ref.watch(dioProvider)));

/// The automatic invoice of a sale, null while it is being created
/// (« Facture en cours de création »). Asked again on each open.
final invoiceForSaleProvider =
    FutureProvider.autoDispose.family<Invoice?, String>((ref, saleId) {
  return ref.watch(invoiceDataSourceProvider).findBySale(saleId);
});

/// One invoice, for the invoice screen.
final invoiceByIdProvider =
    FutureProvider.autoDispose.family<Invoice, String>((ref, id) {
  return ref.watch(invoiceDataSourceProvider).get(id);
});

/// Downloads the PDF of an invoice and hands it to the system share / open
/// sheet (the export file path, [exportFileSharerProvider]).
typedef InvoicePdfOpener = Future<void> Function(Invoice invoice);

final invoicePdfOpenerProvider = Provider<InvoicePdfOpener>((ref) {
  return (invoice) async {
    final fallback = 'facture-${invoice.number ?? 'brouillon'}.pdf'
        .replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    final pdf = await ref
        .read(invoiceDataSourceProvider)
        .pdf(invoice.id, fallbackName: fallback);
    final temp = await ref.read(exportTempDirProvider.future);
    final sep = Platform.pathSeparator;
    // Only the latest PDF stays on the device: earlier downloads (maybe
    // another account's) are removed first. Own subfolder per download, so
    // the readable file name is kept in the share sheet.
    final root = Directory('${temp.path}${sep}invoices');
    try {
      if (await root.exists()) await root.delete(recursive: true);
    } on FileSystemException catch (e) {
      debugPrint('[Invoicing] temp cleanup failed: $e');
    }
    final dir =
        Directory('${root.path}$sep${DateTime.now().microsecondsSinceEpoch}');
    await dir.create(recursive: true);
    final file = File('${dir.path}$sep${pdf.fileName}');
    await file.writeAsBytes(pdf.bytes, flush: true);
    await ref.read(exportFileSharerProvider)(file);
  };
});
