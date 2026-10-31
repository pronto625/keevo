import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/module/module_records.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_invoicing/data/invoice_repository.dart';
import 'package:keevo_invoicing/data/local_invoice_store.dart';
import 'package:keevo_invoicing/data/remote_invoice_datasource.dart';
import 'package:keevo_invoicing/domain/model/invoice.dart';
import 'package:keevo_invoicing/domain/model/invoicing_models.dart';
import 'package:keevo_invoicing/invoicing_module.dart';

import 'helpers/offline_support.dart';

/// Story 15.5b — the offline-first invoice repository, one test per row of
/// the spec's I/O matrix the device owns (create / issue / pay offline, the
/// sync bringing the number, online calls, PDF / sending offline).

class _Server implements HttpClientAdapter {
  ResponseBody Function(RequestOptions r) handler;
  final List<RequestOptions> requests = [];

  _Server(this.handler);

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

ResponseBody _ok(Object data) => ResponseBody.fromString(
  jsonEncode({'success': true, 'data': data}),
  200,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

ResponseBody _offline(RequestOptions r) =>
    throw DioException.connectionError(requestOptions: r, reason: 'offline');

Map<String, dynamic> _server(
  String id, {
  String status = 'ISSUED',
  String? number = 'FAC-2026-000001',
  String? issueDate = '2026-10-20',
  String? paidAt,
}) => {
  'id': id,
  'number': status == 'DRAFT' ? null : number,
  'status': status,
  'statusLabel': invoiceStatusLabel(status),
  'issueDate': status == 'DRAFT' ? null : issueDate,
  'client': {'name': 'Awa Traoré', 'phone': '+237690000001'},
  'lines': [
    {
      'position': 1,
      'description': 'Sac de riz',
      'quantity': 2,
      'unitPrice': 7500,
      'vatTreatment': 'STANDARD',
      'totalHt': 15000,
      'totalVat': 0,
      'totalTtc': 15000,
    },
  ],
  'totalHt': 15000,
  'totalVat': 0,
  'totalTtc': 15000,
  'paidAt': paidAt,
  'createdAt': '2026-10-20T08:00:00Z',
  'updatedAt': '2026-10-20T09:00:00Z',
};

const _input = InvoiceDraftInput(
  clientName: 'Awa Traoré',
  clientPhone: '+237690000001',
  lines: [
    InvoiceLineInput(description: 'Sac de riz', quantity: 2, unitPrice: 7500),
    InvoiceLineInput(description: 'Huile', quantity: 1.5, unitPrice: 1001),
  ],
);

void main() {
  setUpAll(useSystemSqlite);

  late AppDatabase db;
  late _Server server;
  late FakeConnectivity connectivity;
  late FakeModuleSyncQueue queue;
  late LocalInvoiceStore local;
  late InvoiceRepository repo;
  var ids = 0;
  final now = DateTime.utc(2026, 10, 31, 9); // 10:00 in Douala

  setUp(() {
    ids = 0;
    db = AppDatabase.forTesting();
    server = _Server(_offline);
    connectivity = FakeConnectivity(online: false);
    queue = FakeModuleSyncQueue();
    local = LocalInvoiceStore(ModuleRecordStore(db));
    repo = InvoiceRepository(
      remote: RemoteInvoiceDataSource(
        dio: Dio(BaseOptions(baseUrl: 'https://api.test'))
          ..httpClientAdapter = server,
      ),
      local: local,
      db: db,
      queue: queue,
      connectivity: connectivity,
      newId: () => 'inv-${++ids}',
      now: () => now,
    );
  });

  tearDown(() => db.close());

  group('offline', () {
    test('create: draft stored « En attente de synchronisation », '
        'CREATE_INVOICE queued with the device id, a sync requested', () async {
      final draft = await repo.create(_input);

      expect(draft.pending, isTrue);
      expect(draft.isDraft, isTrue);
      expect(draft.number, isNull);
      // provisional total: quantity × unit price (server recomputes VAT)
      expect(draft.totalTtc, 15000 + 1502);
      final stored = await local.get(draft.id);
      expect(stored!.pending, isTrue);
      expect(stored.clientName, 'Awa Traoré');
      expect(stored.lines.map((l) => l.position), [1, 2]);

      final op = queue.queued.single;
      expect(op.operation, 'CREATE_INVOICE');
      expect(op.entityId, draft.id);
      expect(op.payload['id'], draft.id);
      expect(op.payload['client'], containsPair('name', 'Awa Traoré'));
      expect((op.payload['lines'] as List).first, {
        'description': 'Sac de riz',
        'quantity': 2,
        'unitPrice': 7500,
        'vatTreatment': 'STANDARD',
      });
      expect(queue.syncRequests, 1);
      expect(server.requests, isEmpty);
    });

    test('issue: « Émise — numéro attribué à la synchronisation », '
        'ISSUE_INVOICE queued after the creation', () async {
      final draft = await repo.create(_input);
      final issued = await repo.issue(draft.id);

      expect(issued.status, kInvoiceIssued);
      expect(issued.number, isNull);
      expect(issued.awaitingNumber, isTrue);
      expect(
        issued.statusLabel,
        'Émise — numéro attribué à la synchronisation',
      );
      expect(issued.title, 'Numéro à venir');
      expect(queue.operations, ['CREATE_INVOICE', 'ISSUE_INVOICE']);
      expect(queue.queued.last.payload, {'invoiceId': draft.id});
    });

    test('pay after an offline issue, today: no paidAt (dated by the server '
        'at the sync, never before the issue)', () async {
      final draft = await repo.create(_input);
      await repo.issue(draft.id);
      final paid = await repo.pay(draft.id, method: 'Espèces');

      expect(paid.status, kInvoicePaid);
      expect(paid.paidAt, isNotNull);
      expect(queue.operations, [
        'CREATE_INVOICE',
        'ISSUE_INVOICE',
        'PAY_INVOICE',
      ]);
      expect(queue.queued.last.payload, {
        'invoiceId': draft.id,
        'paymentMethod': 'Espèces',
      });
    });

    test('pay an invoice issued on the server: today sent as its Douala day; '
        'a picked day sent as is', () async {
      await seedInvoices(db, [_server('srv-1'), _server('srv-2')]);

      await repo.pay('srv-1', method: 'Mobile Money');
      expect(queue.queued.last.payload, {
        'invoiceId': 'srv-1',
        'paymentMethod': 'Mobile Money',
        'paidAt': '2026-10-31',
      });

      await repo.pay(
        'srv-2',
        method: 'Espèces',
        paidOn: DateTime(2026, 10, 25),
      );
      expect(queue.queued.last.payload['paidAt'], '2026-10-25');
      expect((await local.get('srv-2'))!.paidAt!.day, 25);
    });

    test('update and cancel queued with their content', () async {
      await seedInvoices(db, [_server('d-1', status: 'DRAFT'), _server('i-1')]);
      final updated = await repo.update('d-1', _input);
      expect(updated.totalTtc, 16502);
      expect(queue.queued.last.operation, 'UPDATE_INVOICE');
      expect(queue.queued.last.payload['invoiceId'], 'd-1');
      expect(queue.queued.last.payload['lines'], hasLength(2));

      final cancelled = await repo.cancel('i-1', '  Erreur de client ');
      expect(cancelled.status, kInvoiceCancelled);
      expect(cancelled.cancellationReason, 'Erreur de client');
      expect(queue.queued.last.payload, {
        'invoiceId': 'i-1',
        'reason': 'Erreur de client',
      });
    });

    test('PDF, WhatsApp, credit note, deletion: « Disponible une fois '
        'connecté »', () async {
      await seedInvoices(db, [_server('i-1'), _server('d-1', status: 'DRAFT')]);
      await expectLater(
        repo.requireServer('i-1'),
        throwsA(
          isA<InvoiceUnavailableException>().having(
            (e) => e.message,
            'message',
            'Disponible une fois connecté',
          ),
        ),
      );
      await expectLater(
        repo.delete('d-1'),
        throwsA(isA<InvoiceUnavailableException>()),
      );
      expect(await local.get('d-1'), isNotNull);
      expect(server.requests, isEmpty);
    });

    test(
      'the queue refuses (account read-only): nothing written locally',
      () async {
        queue.enqueueError = StateError('read-only');
        await expectLater(repo.create(_input), throwsA(isA<StateError>()));
        expect(await local.watchAll().first, isEmpty);
      },
    );
  });

  group('sync', () {
    test('the pull brings the number and the status: replaces the local copy '
        'once nothing of it is queued (FAC-…, no longer pending)', () async {
      final draft = await repo.create(_input);
      await repo.issue(draft.id);
      await repo.pay(draft.id, method: 'Espèces');
      final merge = invoicingModuleUi.pulledEntities['invoices']!;
      final pulled = _server(
        draft.id,
        status: 'PAID',
        number: 'FAC-2026-000009',
        paidAt: '2026-10-31T09:00:00Z',
      );

      // pull before the push applied the queue: the local copy is kept
      await merge(ModuleRecordStore(db), [
        pulled,
      ], await queue.unsyncedEntityIds());
      expect((await local.get(draft.id))!.number, isNull);
      expect((await local.get(draft.id))!.pending, isTrue);

      // the push applied them: the next pull replaces it
      queue.clear();
      await merge(ModuleRecordStore(db), [
        pulled,
      ], await queue.unsyncedEntityIds());
      final synced = (await local.get(draft.id))!;
      expect(synced.number, 'FAC-2026-000009');
      expect(synced.statusLabel, 'Payée');
      expect(synced.pending, isFalse);
    });

    test('a change of an invoice with queued operations is queued too, even '
        'online (it applies after them)', () async {
      final draft = await repo.create(_input);
      connectivity.online = true;
      server.handler = (r) => _ok(_server(draft.id));

      await repo.issue(draft.id);

      expect(server.requests, isEmpty);
      expect(queue.operations, ['CREATE_INVOICE', 'ISSUE_INVOICE']);
      await expectLater(
        repo.requireServer(draft.id),
        throwsA(
          isA<InvoiceUnavailableException>().having(
            (e) => e.message,
            'message',
            'Disponible une fois la facture synchronisée',
          ),
        ),
      );
    });
  });

  group('online', () {
    setUp(() => connectivity.online = true);

    test('create: POST with the device id, the answer stored (not pending), '
        'nothing queued', () async {
      server.handler = (r) => _ok(_server('inv-1', status: 'DRAFT'));
      final created = await repo.create(_input);

      final body = Map<String, dynamic>.from(
        server.requests.single.data as Map,
      );
      expect(body['id'], 'inv-1');
      expect(created.pending, isFalse);
      expect((await local.get('inv-1'))!.pending, isFalse);
      expect(queue.queued, isEmpty);
    });

    test('issue: numbered at once by the server, stored', () async {
      await seedInvoices(db, [_server('d-1', status: 'DRAFT')]);
      server.handler = (r) => _ok(_server('d-1', number: 'FAC-2026-000002'));
      final issued = await repo.issue('d-1');
      expect(issued.number, 'FAC-2026-000002');
      expect((await local.get('d-1'))!.number, 'FAC-2026-000002');
      expect(queue.queued, isEmpty);
    });

    test('server unreachable (connection error): written locally and '
        'queued instead of failing', () async {
      await seedInvoices(db, [_server('i-1')]);
      server.handler = _offline;
      final paid = await repo.pay('i-1', method: 'Espèces');
      expect(paid.pending, isTrue);
      expect(queue.operations, ['PAY_INVOICE']);
    });

    test('a refusal of the server (400) is shown, nothing queued', () async {
      await seedInvoices(db, [_server('i-1')]);
      server.handler = (r) => ResponseBody.fromString(
        jsonEncode({
          'success': false,
          'error': 'Date de paiement invalide',
          'domainCode': 'INVOICE_PAYMENT_DATE_INVALID',
        }),
        400,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
      await expectLater(
        repo.pay('i-1', method: 'Espèces', paidOn: DateTime(2026, 1, 1)),
        throwsA(isA<DioException>()),
      );
      expect(queue.queued, isEmpty);
      expect((await local.get('i-1'))!.status, kInvoiceIssued);
    });

    test('refresh: the server copy replaces a synced local one; a pending '
        'one is never fetched', () async {
      await seedInvoices(db, [_server('i-1')]);
      server.handler = (r) => _ok(_server('i-1', status: 'PAID'));
      await repo.refresh('i-1');
      expect((await local.get('i-1'))!.status, kInvoicePaid);

      server.handler = (r) =>
          _ok(_server((r.data as Map)['id'] as String, status: 'DRAFT'));
      final draft = await repo.create(_input); // online POST
      server.requests.clear();
      queue.queued.add((
        operation: 'UPDATE_INVOICE',
        payload: const {},
        entityId: draft.id,
      ));
      await repo.refresh(draft.id);
      expect(server.requests, isEmpty);
    });

    test('refresh offline of an invoice not on the device: « Disponible une '
        'fois connecté »', () async {
      connectivity.online = false;
      await expectLater(
        repo.refresh('unknown'),
        throwsA(isA<InvoiceUnavailableException>()),
      );
    });
  });

  group('review 15.5b', () {
    ResponseBody status(int code) => ResponseBody.fromString(
      jsonEncode({'success': false, 'error': 'x', 'domainCode': 'X'}),
      code,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );

    test('contract keevo/contracts/invoice-sync-payloads.json: the payloads '
        'queued are exactly the shared ones', () async {
      final contract =
          jsonDecode(
                File(
                  '../../../contracts/invoice-sync-payloads.json',
                ).readAsStringSync(),
              )
              as Map<String, dynamic>;
      final id = contract['invoiceId'] as String;
      final expected = {
        for (final op in contract['operations'] as List)
          (op as Map)['name'] as String: (
            op['operationType'] as String,
            op['payload'] as Map<String, dynamic>,
          ),
      };
      const input = InvoiceDraftInput(
        clientName: 'Awa Traoré',
        clientPhone: '+237690000001',
        lines: [
          InvoiceLineInput(
            description: 'Sac de riz 25 kg',
            quantity: 2,
            unitPrice: 11925,
          ),
          InvoiceLineInput(
            description: 'Huile 5 L',
            quantity: 1.5,
            unitPrice: 6500,
            vatTreatment: kVatExempt,
          ),
        ],
      );
      final repository = InvoiceRepository(
        remote: RemoteInvoiceDataSource(dio: Dio()..httpClientAdapter = server),
        local: local,
        db: db,
        queue: queue,
        connectivity: connectivity,
        newId: () => id,
        now: () => now,
      );
      final got = <String, (String, Map<String, dynamic>)>{};
      void take(String name) {
        final q = queue.queued.last;
        got[name] = (q.operation, q.payload);
      }

      await repository.create(input);
      take('create');
      await repository.update(id, input);
      take('update');
      await repository.issue(id);
      take('issue');
      await repository.pay(id, method: 'Espèces');
      take('payWhileIssueQueued');

      // the same invoice, issued on the server (another device)
      queue.clear();
      await local.delete(id);
      await seedInvoices(db, [_server(id)]);
      await repository.pay(
        id,
        method: 'Mobile Money',
        paidOn: DateTime(2026, 10, 25),
      );
      take('payBackDated');
      await local.delete(id);
      await seedInvoices(db, [_server(id)]);
      await repository.cancel(id, 'Erreur de client');
      take('cancel');

      expect(got.keys.toSet(), expected.keys.toSet());
      for (final name in expected.keys) {
        expect(got[name]!.$1, expected[name]!.$1, reason: name);
        expect(
          jsonDecode(jsonEncode(got[name]!.$2)),
          expected[name]!.$2,
          reason: name,
        );
      }
    });

    test('offline issue: client name and a line checked locally', () async {
      final noLine = _server('d-1', status: 'DRAFT')..['lines'] = [];
      final noName = _server('d-2', status: 'DRAFT')
        ..['client'] = {'name': ' '};
      await seedInvoices(db, [noLine, noName]);
      await expectLater(
        repo.issue('d-1'),
        throwsA(
          isA<InvoiceUnavailableException>().having(
            (e) => e.message,
            'message',
            'Ajoutez au moins une ligne',
          ),
        ),
      );
      await expectLater(
        repo.issue('d-2'),
        throwsA(
          isA<InvoiceUnavailableException>().having(
            (e) => e.message,
            'message',
            'Indiquez le nom du client',
          ),
        ),
      );
      expect(queue.queued, isEmpty);
    });

    test(
      'local transitions checked before queuing; cancel reason required',
      () async {
        await seedInvoices(db, [
          _server('d-1', status: 'DRAFT'),
          _server('p-1', status: 'PAID', paidAt: '2026-10-21T10:00:00Z'),
        ]);
        Matcher refused = throwsA(isA<InvoiceUnavailableException>());
        await expectLater(repo.pay('d-1', method: 'Espèces'), refused);
        await expectLater(repo.cancel('d-1', 'Erreur'), refused);
        await expectLater(repo.issue('p-1'), refused);
        await expectLater(repo.update('p-1', _input), refused);
        await expectLater(repo.pay('p-1', method: 'Espèces'), refused);
        await expectLater(repo.cancel('p-1', '   '), refused);
        expect(queue.queued, isEmpty);
      },
    );

    test(
      'pay while the issue is still queued: only today, no paidAt sent',
      () async {
        final draft = await repo.create(_input);
        await repo.issue(draft.id);
        await expectLater(
          repo.pay(draft.id, method: 'Espèces', paidOn: DateTime(2026, 10, 25)),
          throwsA(isA<InvoiceUnavailableException>()),
        );
        await repo.pay(
          draft.id,
          method: 'Espèces',
          paidOn: DateTime(2026, 10, 31),
        );
        expect(queue.queued.last.payload.containsKey('paidAt'), isFalse);
      },
    );

    test('a pending local copy counts as queued (e.g. a quarantined '
        'operation): not online-ready', () async {
      connectivity.online = true;
      await seedInvoices(db, [_server('i-1')], pending: true);
      expect(await repo.hasQueued('i-1'), isTrue);
      await expectLater(
        repo.requireServer('i-1'),
        throwsA(
          isA<InvoiceUnavailableException>().having(
            (e) => e.message,
            'message',
            'Disponible une fois la facture synchronisée',
          ),
        ),
      );
    });

    test('a draft created offline, never sent: deleted on the device with '
        'its queued operations (online or not)', () async {
      final draft = await repo.create(_input);
      await repo.update(draft.id, _input);
      await repo.delete(draft.id);
      expect(await local.get(draft.id), isNull);
      expect(queue.queued, isEmpty);
      expect(server.requests, isEmpty);
    });

    test('refresh: 404 removes a confirmed local copy; not on the device but '
        'its creation queued → « En attente de synchronisation »', () async {
      connectivity.online = true;
      await seedInvoices(db, [_server('gone')]);
      server.handler = (r) => status(404);
      await repo.refresh('gone');
      expect(await local.get('gone'), isNull);

      queue.queued.add((
        operation: 'CREATE_INVOICE',
        payload: const {},
        entityId: 'ghost',
      ));
      await expectLater(
        repo.refresh('ghost'),
        throwsA(
          isA<InvoiceUnavailableException>().having(
            (e) => e.message,
            'message',
            'En attente de synchronisation',
          ),
        ),
      );
    });

    test(
      'create: 503 / TLS failure → queued (idempotent); 500 → shown',
      () async {
        connectivity.online = true;
        server.handler = (r) => status(503);
        expect((await repo.create(_input)).pending, isTrue);
        server.handler = (r) => throw DioException(
          requestOptions: r,
          error: const HandshakeException('tls'),
        );
        expect((await repo.create(_input)).pending, isTrue);
        expect(queue.operations, ['CREATE_INVOICE', 'CREATE_INVOICE']);

        server.handler = (r) => status(500);
        await expectLater(repo.create(_input), throwsA(isA<DioException>()));
        // a non-idempotent call is not queued on a 503
        await seedInvoices(db, [_server('i-1')]);
        server.handler = (r) => status(503);
        await expectLater(
          repo.pay('i-1', method: 'Espèces'),
          throwsA(isA<DioException>()),
        );
      },
    );

    test('the invoice of a sale is found on the device', () async {
      await seedInvoices(db, [
        _server('old')
          ..['saleId'] = 's-1'
          ..['supersededAt'] = '2026-10-25T10:00:00Z',
        _server('new')..['saleId'] = 's-1',
      ]);
      expect((await repo.localForSale('s-1'))!.id, 'new');
      expect(await repo.localForSale('s-2'), isNull);
    });
  });
}
