import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/module/module_ports.dart';
import 'package:keevo_core/module/module_records.dart';
import 'package:keevo_core/module/module_sync.dart';

import '../../data/invoice_repository.dart';
import '../../data/local_invoice_store.dart';
import '../../data/remote_invoice_datasource.dart';
import '../../domain/model/invoice.dart';
import '../../domain/model/invoicing_models.dart';

/// HTTP adapter of the invoices API (the app's API client, [apiDioProvider]
/// port).
final invoiceDataSourceProvider = Provider<RemoteInvoiceDataSource>(
  (ref) => RemoteInvoiceDataSource(dio: ref.watch(apiDioProvider)),
);

/// Story 15.5b — the invoices kept on the device (module records of the
/// app's database).
final localInvoiceStoreProvider = Provider<LocalInvoiceStore>(
  (ref) => LocalInvoiceStore(ModuleRecordStore(ref.watch(appDatabaseProvider))),
);

/// Story 15.5b — offline-first invoices: local reads, online calls or
/// queued sync operations ([moduleSyncQueueProvider] port).
final invoiceRepositoryProvider = Provider<InvoiceRepository>(
  (ref) => InvoiceRepository(
    remote: ref.watch(invoiceDataSourceProvider),
    local: ref.watch(localInvoiceStoreProvider),
    db: ref.watch(appDatabaseProvider),
    queue: ref.watch(moduleSyncQueueProvider),
    connectivity: ref.watch(connectivityServiceProvider),
  ),
);

/// Story 15.5b — every invoice on the device, updated by each local write
/// and each sync pull.
final localInvoicesProvider = StreamProvider.autoDispose<List<Invoice>>(
  (ref) => ref.watch(invoiceRepositoryProvider).watchAll(),
);

/// The automatic invoice of a sale, null while it is being created
/// (« Facture en cours de création »). Asked again on each open.
final invoiceForSaleProvider = FutureProvider.autoDispose
    .family<Invoice?, String>((ref, saleId) async {
      // Story 15.5b — the invoice already on the device first (offline too)
      final local = await ref
          .watch(invoiceRepositoryProvider)
          .localForSale(saleId);
      if (local != null) return local;
      return ref.watch(invoiceDataSourceProvider).findBySale(saleId);
    });

/// One invoice, for the invoice screen. Story 15.5b: the device's copy is
/// shown at once and followed (a sync or an action updates the screen); the
/// server's copy is fetched in the background when it can (online, nothing
/// of the invoice queued). Not on the device yet: fetched first (its error
/// shown — « En attente de synchronisation » while its creation is queued).
final invoiceByIdProvider = StreamProvider.autoDispose.family<Invoice, String>((
  ref,
  id,
) async* {
  final repository = ref.watch(invoiceRepositoryProvider);
  if (await repository.local(id) == null) {
    await repository.refresh(id);
  } else {
    unawaited(
      repository.refresh(id).catchError((Object e) {
        debugPrint('[Invoicing] background refresh failed: $e');
      }),
    );
  }
  var shown = false;
  await for (final invoice in repository.watch(id)) {
    if (invoice == null) {
      // deleted while shown (a draft deleted from this screen, or on the
      // server): the screen keeps the last copy while it leaves
      if (shown) return;
      throw const InvoiceUnavailableException('Facture introuvable');
    }
    shown = true;
    yield invoice;
  }
});

/// Downloads the PDF of an invoice and hands it to the system share / open
/// sheet (the app's file share path, [fileSharerProvider] port).
typedef InvoicePdfOpener = Future<void> Function(Invoice invoice);

final invoicePdfOpenerProvider = Provider<InvoicePdfOpener>((ref) {
  return (invoice) async {
    // Story 15.5b — the PDF is the server's: online, synced invoice only.
    await ref.read(invoiceRepositoryProvider).requireServer(invoice.id);
    final fallback = 'facture-${invoice.number ?? 'brouillon'}.pdf';
    final pdf = await ref
        .read(invoiceDataSourceProvider)
        .pdf(invoice.id, fallbackName: _safeName(fallback));
    await _shareDownloadedPdf(ref, pdf);
  };
});

/// Story 15.5 — same for a credit note (« Voir le PDF » of the invoice's
/// credit notes).
typedef CreditNotePdfOpener = Future<void> Function(CreditNoteSummary note);

final creditNotePdfOpenerProvider = Provider<CreditNotePdfOpener>((ref) {
  return (note) async {
    // Story 15.5b — credit notes are online-only.
    if (!await _online(ref)) throw InvoiceUnavailableException.offline;
    final fallback = 'avoir-${note.number ?? 'avoir'}.pdf';
    final pdf = await ref
        .read(invoiceDataSourceProvider)
        .creditNotePdf(note.id, fallbackName: _safeName(fallback));
    await _shareDownloadedPdf(ref, pdf);
  };
});

Future<bool> _online(Ref ref) async {
  try {
    return await ref.read(connectivityServiceProvider).isOnline();
  } catch (_) {
    return true;
  }
}

String _safeName(String name) =>
    name.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');

Future<void> _shareDownloadedPdf(Ref ref, InvoicePdf pdf) async {
  final temp = await ref.read(tempDirProvider.future);
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
  final dir = Directory(
    '${root.path}$sep${DateTime.now().microsecondsSinceEpoch}',
  );
  await dir.create(recursive: true);
  final file = File('${dir.path}$sep${pdf.fileName}');
  await file.writeAsBytes(pdf.bytes, flush: true);
  await ref.read(fileSharerProvider)(file);
}

/// Story 15.5 — the seller profile (settings screen, issue warnings).
final invoicingSettingsProvider = FutureProvider.autoDispose<InvoicingSettings>(
  (ref) {
    return ref.watch(invoiceDataSourceProvider).settings();
  },
);

/// Story 15.5 — CRM clients matching [search] for the invoice client picker;
/// `crmAvailable` false → free-text client fields.
final clientSuggestionsProvider = FutureProvider.autoDispose
    .family<ClientSuggestions, String>((ref, search) async {
      // Story 15.5b — offline: no request (and no network timeout)
      if (!await _online(ref)) throw InvoiceUnavailableException.offline;
      return ref.watch(invoiceDataSourceProvider).clients(search);
    });

/// Story 15.5 — quantity already credited per invoice line position, from
/// the credit notes [creditNoteIds] of an invoice (`GET /credit-notes/{id}`
/// each; key: [creditNoteKey]): what remains to credit on a line = its
/// quantity minus this.
final creditedQuantitiesProvider = FutureProvider.autoDispose
    .family<Map<int, num>, String>((ref, creditNoteIds) async {
      final ds = ref.watch(invoiceDataSourceProvider);
      final ids = creditNoteIds.split(',').where((id) => id.isNotEmpty);
      final notes = await Future.wait([
        for (final id in ids) ds.creditNote(id),
      ]);
      final credited = <int, num>{};
      for (final note in notes) {
        for (final line in note.lines) {
          credited[line.invoicePosition] =
              (credited[line.invoicePosition] ?? 0) + line.quantity;
        }
      }
      return credited;
    });

/// Key of [creditedQuantitiesProvider] for [invoice].
String creditNoteKey(Invoice invoice) =>
    invoice.creditNotes.map((n) => n.id).join(',');
