import 'package:keevo_core/module/module_records.dart';

import '../domain/model/invoice.dart';

/// Module id of the backend manifest — the `module` of the local records.
const kInvoicingRecordsModule = 'invoicing';

/// Entity of the invoices in the module records, and their key in the sync
/// pull (`InvoiceDeltaProvider` on the server).
const kInvoicesEntity = 'invoices';

/// Story 15.5b — the invoices kept on the device (`module_records`): every
/// invoice the pull or an online call brought, in the API's shape, plus the
/// ones created or changed offline (`pending`) until the server confirms
/// them.
class LocalInvoiceStore {
  final ModuleRecordStore _store;

  LocalInvoiceStore(this._store);

  ModuleRecordStore get records => _store;

  static Invoice? _invoice(ModuleRecord? record) {
    if (record == null) return null;
    try {
      return Invoice.fromJson(record.json).withPending(record.pending);
    } on FormatException {
      return null; // an unreadable row is skipped, never shown half
    }
  }

  Future<Invoice?> get(String id) async =>
      _invoice(await _store.get(kInvoicingRecordsModule, kInvoicesEntity, id));

  /// The raw stored JSON of [id] (local changes start from it).
  Future<Map<String, dynamic>?> json(String id) async =>
      (await _store.get(kInvoicingRecordsModule, kInvoicesEntity, id))?.json;

  Stream<Invoice?> watch(String id) => _store
      .watchOne(kInvoicingRecordsModule, kInvoicesEntity, id)
      .map(_invoice);

  /// Every invoice on the device (one read).
  Future<List<Invoice>> all() async => [
    for (final r in await _store.list(kInvoicingRecordsModule, kInvoicesEntity))
      if (_invoice(r) case final Invoice i) i,
  ];

  Stream<List<Invoice>> watchAll() => _store
      .watch(kInvoicingRecordsModule, kInvoicesEntity)
      .map(
        (records) => [
          for (final r in records)
            if (_invoice(r) case final Invoice i) i,
        ],
      );

  /// The server's copy of [invoice] (an online call answered it): replaces
  /// the local one, no longer pending.
  Future<void> saveServer(Invoice invoice) => _store.put(
    kInvoicingRecordsModule,
    kInvoicesEntity,
    invoice.id,
    invoice.toJson(),
    updatedAt: invoice.updatedAt,
  );

  /// A change made on this device, waiting for its queued operation;
  /// [updatedAt] = the time of the change (the repository's clock).
  Future<void> saveLocal(
    String id,
    Map<String, dynamic> json, {
    DateTime? updatedAt,
  }) => _store.put(
    kInvoicingRecordsModule,
    kInvoicesEntity,
    id,
    json,
    updatedAt: updatedAt,
    pending: true,
  );

  Future<void> delete(String id) =>
      _store.delete(kInvoicingRecordsModule, kInvoicesEntity, id);
}
