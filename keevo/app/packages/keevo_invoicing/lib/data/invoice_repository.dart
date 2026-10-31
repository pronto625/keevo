import 'dart:io';

import 'package:dio/dio.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_core/sync/connectivity_service.dart';
import 'package:uuid/uuid.dart';

import '../domain/model/invoice.dart';
import '../domain/model/invoicing_models.dart';
import 'local_invoice_store.dart';
import 'remote_invoice_datasource.dart';

/// Sync operations of the offline invoices (`InvoiceSyncHandler` on the
/// server, module `invoicing`).
const kCreateInvoiceOp = 'CREATE_INVOICE';
const kUpdateInvoiceOp = 'UPDATE_INVOICE';
const kIssueInvoiceOp = 'ISSUE_INVOICE';
const kPayInvoiceOp = 'PAY_INVOICE';
const kCancelInvoiceOp = 'CANCEL_INVOICE';

/// Story 15.5b — an action that needs the server (PDF, WhatsApp, credit
/// note, deleting a draft) asked while it cannot be done: [message] is the
/// French reason shown to the user.
class InvoiceUnavailableException implements Exception {
  final String message;

  const InvoiceUnavailableException(this.message);

  /// No network.
  static const offline = InvoiceUnavailableException(
    'Disponible une fois connecté',
  );

  /// The invoice still has changes waiting for the sync (its number, its
  /// PDF… are the server's).
  static const notSynced = InvoiceUnavailableException(
    'Disponible une fois la facture synchronisée',
  );

  /// Not on the device, its creation still queued.
  static const pendingSync = InvoiceUnavailableException(
    'En attente de synchronisation',
  );

  @override
  String toString() => message;
}

/// Story 15.5b — invoices offline-first, like the app's other records.
///
/// Reads come from the device ([LocalInvoiceStore], filled by the sync pull
/// and by every online answer). Writes:
/// * online, and no operation of the invoice still queued: the API call
///   (`/api/v1/invoices`…) as before, its answer stored on the device;
/// * otherwise (no network, the server unreachable, or earlier changes of
///   the invoice still queued — they must apply first): the change is
///   written on the device (« En attente de synchronisation ») and its sync
///   operation queued, in one transaction; a sync is requested.
///
/// Numbering stays on the server: an invoice issued offline has no number
/// until the sync (« Émise — numéro attribué à la synchronisation »). The
/// PDF, the WhatsApp sending and credit notes are online-only.
class InvoiceRepository {
  final RemoteInvoiceDataSource _remote;
  final LocalInvoiceStore _local;
  final AppDatabase _db;
  final ModuleSyncQueue _queue;
  final ConnectivityService _connectivity;
  final String Function() _newId;
  final DateTime Function() _now;

  InvoiceRepository({
    required RemoteInvoiceDataSource remote,
    required LocalInvoiceStore local,
    required AppDatabase db,
    required ModuleSyncQueue queue,
    required ConnectivityService connectivity,
    String Function()? newId,
    DateTime Function()? now,
  }) : _remote = remote,
       _local = local,
       _db = db,
       _queue = queue,
       _connectivity = connectivity,
       _newId = newId ?? const Uuid().v4,
       _now = now ?? DateTime.now;

  RemoteInvoiceDataSource get remote => _remote;

  // ── reads ─────────────────────────────────────────────────────────────

  /// Every invoice on the device, emitted again on each change.
  Stream<List<Invoice>> watchAll() => _local.watchAll();

  /// The invoice [id] on the device, emitted again on each change (null
  /// once deleted).
  Stream<Invoice?> watch(String id) => _local.watch(id);

  /// Brings the server's copy of [id] when it can (online, nothing of it
  /// queued): fresher sendings, credit notes… Without a local copy (an
  /// invoice not pulled yet, e.g. the one of a sale just made) the server's
  /// error is thrown; with one, a failure keeps the local copy.
  ///
  /// Story 15.5b review: a 404 removes a confirmed local copy (deleted on
  /// the server); the server copy is stored only if nothing of the invoice
  /// was queued meanwhile (re-checked in the write transaction).
  Future<void> refresh(String id) async {
    final local = await _local.get(id);
    if (await hasQueued(id)) {
      if (local == null) throw InvoiceUnavailableException.pendingSync;
      return;
    }
    if (!await _online()) {
      if (local == null) throw InvoiceUnavailableException.offline;
      return;
    }
    Invoice server;
    try {
      server = await _remote.get(id);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404 && local != null) {
        await _db.transaction(() async {
          if (!await hasQueued(id)) await _local.delete(id);
        });
        return;
      }
      if (local == null) rethrow;
      return;
    } catch (_) {
      if (local == null) rethrow;
      return;
    }
    await _db.transaction(() async {
      if (!await hasQueued(id)) await _local.saveServer(server);
    });
  }

  /// The device's copy of [id], null when absent.
  Future<Invoice?> local(String id) => _local.get(id);

  /// Story 15.5b review — the invoice of the sale [saleId] on the device
  /// (the active one when a correction replaced an earlier one), null when
  /// none was pulled yet.
  Future<Invoice?> localForSale(String saleId) async {
    final all = [
      for (final i in await _local.all())
        if (i.saleId == saleId) i,
    ];
    if (all.isEmpty) return null;
    for (final i in all) {
      if (i.supersededAt == null) return i;
    }
    return all.first;
  }

  // ── writes ────────────────────────────────────────────────────────────

  /// A new draft. Its id is chosen here: the online call and the queued
  /// creation use the same one (the server never makes two invoices).
  Future<Invoice> create(InvoiceDraftInput input) async {
    final id = _newId();
    if (await _online()) {
      try {
        final created = await _remote.create(input, id: id);
        await _local.saveServer(created);
        return created;
      } on DioException catch (e) {
        // the creation is idempotent on its id: any lost answer is retried
        if (!_unreachable(e, idempotent: true)) rethrow;
      }
    }
    final now = _now();
    final json = <String, dynamic>{
      'id': id,
      'number': null,
      'status': kInvoiceDraft,
      'statusLabel': invoiceStatusLabel(kInvoiceDraft),
      'createdAt': now.toUtc().toIso8601String(),
      ..._content(input),
    };
    return _queued(id, json, kCreateInvoiceOp, {'id': id, ...input.toJson()});
  }

  /// Replaces the content of the draft [id].
  Future<Invoice> update(String id, InvoiceDraftInput input) async {
    await _expectStatus(id, kInvoiceDraft);
    final online = await _onlinePath(id);
    if (online) {
      try {
        final updated = await _remote.update(id, input);
        await _local.saveServer(updated);
        return updated;
      } on DioException catch (e) {
        if (!_unreachable(e)) rethrow;
      }
    }
    final json = {...await _require(id), ..._content(input)};
    return _queued(id, json, kUpdateInvoiceOp, {
      'invoiceId': id,
      ...input.toJson(),
    });
  }

  /// Issues the draft [id]: online, numbered at once; offline, issued on the
  /// device and numbered by the server at the sync.
  Future<Invoice> issue(String id) async {
    final current = await _expectStatus(id, kInvoiceDraft);
    if (await _onlinePath(id)) {
      try {
        final issued = await _remote.issue(id);
        await _local.saveServer(issued);
        return issued;
      } on DioException catch (e) {
        if (!_unreachable(e)) rethrow;
      }
    }
    // the server checks the rest (seller settings) at the sync
    final client = current['client'];
    final name = client is Map ? client['name'] : null;
    if (name is! String || name.trim().isEmpty) {
      throw const InvoiceUnavailableException('Indiquez le nom du client');
    }
    final lines = current['lines'];
    if (lines is! List || lines.isEmpty) {
      throw const InvoiceUnavailableException('Ajoutez au moins une ligne');
    }
    final json = {
      ...current,
      'status': kInvoiceIssued,
      'statusLabel': invoiceStatusLabel(kInvoiceIssued),
      'number': null,
      'complianceWarnings': const <String>[],
    };
    return _queued(id, json, kIssueInvoiceOp, {'invoiceId': id});
  }

  /// Records the payment of [id]: [paidOn] = the day the client paid (null
  /// = today), [method] as the user named it.
  ///
  /// Queued offline, today's payment is sent with its day once the invoice
  /// has its issue date from the server (the sync may happen on a later
  /// day); an invoice whose issue is queued too gets its payment dated by the
  /// server at the sync — its issue date is only known then, and a payment
  /// can never precede it.
  Future<Invoice> pay(String id, {String? method, DateTime? paidOn}) async {
    final before = await _expectStatus(id, kInvoiceIssued);
    if (await _onlinePath(id)) {
      try {
        final paid = await _remote.pay(
          id,
          paymentMethod: method,
          paidOn: paidOn,
        );
        await _local.saveServer(paid);
        return paid;
      } on DioException catch (e) {
        if (!_unreachable(e)) rethrow;
      }
    }
    final current = before;
    final issuedOnServer = current['issueDate'] is String;
    if (!issuedOnServer &&
        paidOn != null &&
        !_sameDay(paidOn, doualaToday(now: _now()))) {
      // its issue date is the sync day: an earlier payment would be refused
      throw const InvoiceUnavailableException(
        'Facture émise hors ligne : le paiement ne peut être daté que '
        'd\'aujourd\'hui',
      );
    }
    final day = issuedOnServer ? (paidOn ?? doualaToday(now: _now())) : null;
    final trimmed = method?.trim();
    final paidAt = paidOn == null
        ? _now()
        : DateTime(paidOn.year, paidOn.month, paidOn.day, 12);
    final json = {
      ...current,
      'status': kInvoicePaid,
      'statusLabel': invoiceStatusLabel(kInvoicePaid),
      'paidAt': paidAt.toUtc().toIso8601String(),
      'paymentMethod': trimmed == null || trimmed.isEmpty ? null : trimmed,
    };
    return _queued(id, json, kPayInvoiceOp, {
      'invoiceId': id,
      if (trimmed != null && trimmed.isNotEmpty) 'paymentMethod': trimmed,
      if (day != null) 'paidAt': RemoteInvoiceDataSource.isoDay(day),
    });
  }

  /// Cancels the issued invoice [id] (owner), [reason] required.
  Future<Invoice> cancel(String id, String reason) async {
    if (reason.trim().isEmpty) {
      throw const InvoiceUnavailableException(
        'Le motif d\'annulation est requis',
      );
    }
    final current = await _expectStatus(id, kInvoiceIssued);
    if (await _onlinePath(id)) {
      try {
        final cancelled = await _remote.cancel(id, reason);
        await _local.saveServer(cancelled);
        return cancelled;
      } on DioException catch (e) {
        if (!_unreachable(e)) rethrow;
      }
    }
    final json = {
      ...current,
      'status': kInvoiceCancelled,
      'statusLabel': invoiceStatusLabel(kInvoiceCancelled),
      'cancelledAt': _now().toUtc().toIso8601String(),
      'cancellationReason': reason.trim(),
    };
    return _queued(id, json, kCancelInvoiceOp, {
      'invoiceId': id,
      'reason': reason.trim(),
    });
  }

  /// Deletes the draft [id] — online only (no sync operation deletes an
  /// invoice), once nothing of it is queued.
  ///
  /// Story 15.5b review: a draft created offline and never sent (its
  /// creation still queued) is removed from the device with its queued
  /// operations, online or not.
  Future<void> delete(String id) async {
    final operations = await _queue.queuedOperations(id);
    if (operations.contains(kCreateInvoiceOp)) {
      await _expectStatus(id, kInvoiceDraft);
      await _db.transaction(() async {
        await _queue.dropQueued(id);
        await _local.delete(id);
      });
      return;
    }
    await requireServer(id);
    await _remote.delete(id);
    await _local.delete(id);
  }

  /// Throws [InvoiceUnavailableException] unless the server can act on the
  /// invoice now: online, and nothing of it still queued (PDF, WhatsApp,
  /// credit notes, deletion).
  Future<void> requireServer(String id) async {
    if (await hasQueued(id)) {
      _queue.requestSync();
      throw InvoiceUnavailableException.notSynced;
    }
    if (!await _online()) throw InvoiceUnavailableException.offline;
  }

  /// Stores the server's answer of an online action made elsewhere (WhatsApp
  /// sending, credit note…).
  Future<void> saveServer(Invoice invoice) => _local.saveServer(invoice);

  // ── helpers ───────────────────────────────────────────────────────────

  Future<bool> _online() async {
    try {
      return await _connectivity.isOnline();
    } catch (_) {
      return true; // unknown: the call tells (an unreachable server queues)
    }
  }

  /// Whether changes of [id] wait for the sync: an operation of it still
  /// queued, or its local copy still pending (e.g. an operation quarantined,
  /// out of the queued ones).
  Future<bool> hasQueued(String id) async {
    if ((await _queue.unsyncedEntityIds()).contains(id)) return true;
    return (await _local.get(id))?.pending ?? false;
  }

  /// Whether the server can act on [id] now: online and nothing of it
  /// waiting for the sync (offline, the screens explain it).
  Future<bool> canReachServer(String id) async =>
      !await hasQueued(id) && await _online();

  /// Whether a change of [id] goes to the server now: online and no earlier
  /// change of it still queued (it must apply after them).
  Future<bool> _onlinePath(String id) => canReachServer(id);

  static bool _sameDay(DateTime a, DateTime b) =>
      a.year == b.year && a.month == b.month && a.day == b.day;

  /// The local JSON of [id], refused unless its status is [status] (the
  /// server would refuse the transition at the sync).
  Future<Map<String, dynamic>> _expectStatus(String id, String status) async {
    final json = await _require(id);
    if (json['status'] != status) {
      throw InvoiceUnavailableException(switch (status) {
        kInvoiceDraft => 'Seul un brouillon peut être modifié ou émis',
        _ => 'Seule une facture émise et non payée peut être payée ou annulée',
      });
    }
    return json;
  }

  /// The request never reached the server (no network, server unreachable):
  /// the change is queued instead. A timeout after sending is only safe for
  /// an idempotent call (the server may have applied it).
  ///
  /// For the idempotent creation, a gateway error (502 / 503 / 504) and a
  /// TLS / handshake failure count as unreachable too.
  static bool _unreachable(DioException e, {bool idempotent = false}) {
    switch (e.type) {
      case DioExceptionType.connectionError:
      case DioExceptionType.connectionTimeout:
        return true;
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
        return idempotent;
      case DioExceptionType.badResponse:
        return idempotent &&
            const {502, 503, 504}.contains(e.response?.statusCode);
      case DioExceptionType.badCertificate:
        return idempotent;
      case DioExceptionType.unknown:
        return idempotent ? e.error is IOException : e.error is SocketException;
      default:
        return false;
    }
  }

  Future<Map<String, dynamic>> _require(String id) async {
    final json = await _local.json(id);
    if (json == null) {
      throw const InvoiceUnavailableException(
        'Facture introuvable sur cet appareil',
      );
    }
    return Map<String, dynamic>.of(json);
  }

  /// Writes [json] as a pending local change and queues [operation] with
  /// [payload], in one transaction; then asks for a sync.
  Future<Invoice> _queued(
    String id,
    Map<String, dynamic> json,
    String operation,
    Map<String, dynamic> payload,
  ) async {
    final now = _now();
    json['updatedAt'] = now.toUtc().toIso8601String();
    await _db.transaction(() async {
      await _local.saveLocal(id, json, updatedAt: now);
      await _queue.enqueue(
        operation: operation,
        payload: payload,
        entityId: id,
      );
    });
    _queue.requestSync();
    return Invoice.fromJson(json).withPending(true);
  }

  /// Client, lines and provisional totals of a draft written on the device:
  /// TTC = quantity × unit price per line (the server recomputes HT / VAT
  /// with the seller settings at the sync).
  static Map<String, dynamic> _content(InvoiceDraftInput input) {
    var position = 0;
    var total = 0;
    final lines = <Map<String, dynamic>>[];
    for (final l in input.lines) {
      final ttc = (l.quantity * l.unitPrice).round();
      total += ttc;
      lines.add({
        ...l.toJson(),
        'position': ++position,
        'totalHt': ttc,
        'totalVat': 0,
        'totalTtc': ttc,
      });
    }
    final client = input.toJson()['client'];
    return {
      'client': client,
      'lines': lines,
      'totalHt': total,
      'totalVat': 0,
      'totalTtc': total,
      'complianceWarnings': const <String>[],
    };
  }
}
