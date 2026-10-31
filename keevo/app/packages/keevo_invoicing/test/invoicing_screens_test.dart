import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/module/module_ports.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_core/storage/app_database.dart';
import 'package:keevo_invoicing/data/remote_invoice_datasource.dart';
import 'package:keevo_invoicing/domain/model/invoicing_models.dart';
import 'package:keevo_invoicing/presentation/invoicing_format.dart';
import 'package:keevo_invoicing/presentation/page/invoice_detail_page.dart';
import 'package:keevo_invoicing/presentation/page/invoice_form_page.dart';
import 'package:keevo_invoicing/presentation/page/invoice_list_page.dart';
import 'package:keevo_invoicing/presentation/page/invoicing_settings_page.dart';

import 'helpers/offline_support.dart';

/// Story 15.5 — the invoicing screens against a fake HTTP server (real
/// [Dio] + adapter, through the `apiDioProvider` port): one group per row of
/// the spec's I/O matrix. Story 15.5b: with the device's store (in-memory
/// database), a switchable connectivity and a recording sync queue.

typedef _Handler = ResponseBody Function(RequestOptions request);

/// Routes each request to [handler]; records them.
class _Server implements HttpClientAdapter {
  _Handler handler;
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

  Iterable<RequestOptions> where(String method, String pathEnd) =>
      requests.where((r) => r.method == method && r.uri.path.endsWith(pathEnd));
}

ResponseBody _json(Object body, {int status = 200}) => ResponseBody.fromString(
  jsonEncode(body),
  status,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

ResponseBody _ok(Object data) => _json({'success': true, 'data': data});

ResponseBody _error(
  int status,
  String message,
  String code, [
  Object? details,
]) => _json({
  'success': false,
  'error': message,
  'domainCode': code,
  'details': details,
}, status: status);

Map<String, dynamic> _invoice(
  String id, {
  String status = 'ISSUED',
  String? number = 'FAC-2026-000007',
  String client = 'Awa Traoré',
  String? phone = '+237690000001',
  int total = 15000,
  List<Map<String, dynamic>>? lines,
  List<String> warnings = const [],
  List<Map<String, dynamic>> creditNotes = const [],
  int creditedTtc = 0,
  bool fullyCredited = false,
  String? supersededAt,
  String? paidAt,
  int sendCount = 0,
}) => {
  'id': id,
  'number': status == 'DRAFT' ? null : number,
  'status': status,
  'statusLabel': switch (status) {
    'DRAFT' => 'Brouillon',
    'PAID' => 'Payée',
    'CANCELLED' => 'Annulée',
    _ => 'Émise',
  },
  'issueDate': status == 'DRAFT' ? null : '2026-10-20',
  'client': {'id': null, 'name': client, 'phone': phone},
  'lines':
      lines ??
      [
        {
          'position': 1,
          'description': 'Sac de riz',
          'quantity': 1,
          'unitPrice': 15000,
          'vatTreatment': 'STANDARD',
          'totalTtc': 15000,
        },
      ],
  'totalHt': total,
  'totalVat': 0,
  'totalTtc': total,
  'paidAt': paidAt,
  'paymentMethod': paidAt == null ? null : 'Espèces',
  'complianceWarnings': warnings,
  'lastSentAt': sendCount > 0 ? '2026-10-30T10:00:00Z' : null,
  'lastSentTo': sendCount > 0 ? '+237 6•• ••• 001' : null,
  'sendCount': sendCount,
  'creditedTtc': creditedTtc,
  'fullyCredited': fullyCredited,
  'creditNotes': creditNotes,
  'supersededAt': supersededAt,
  'createdAt': '2026-10-20T08:00:00Z',
};

/// The device side of the last [_pump] (15.5b).
late AppDatabase _db;
late FakeConnectivity _connectivity;
late FakeModuleSyncQueue _queue;

/// Pumps [page] with the fake server and the session role; stub routes for
/// the screens it navigates to. Story 15.5b: [seed] = invoices already on
/// the device (as the pull stores them), [online] = the connectivity.
Future<_Server> _pump(
  WidgetTester tester,
  Widget page,
  _Handler handler, {
  bool owner = true,
  bool online = true,
  List<Map<String, dynamic>> seed = const [],
}) async {
  _db = testDatabase();
  addTearDown(_db.close);
  _connectivity = FakeConnectivity(online: online);
  _queue = FakeModuleSyncQueue();
  await tester.runAsync(() => seedInvoices(_db, seed));
  final server = _Server(handler);
  final dio = Dio(BaseOptions(baseUrl: 'https://api.test'))
    ..httpClientAdapter = server;
  final router = GoRouter(
    routes: [
      GoRoute(path: '/', builder: (_, _) => page),
      GoRoute(
        path: '/invoicing',
        builder: (_, _) => const Scaffold(body: Text('LIST')),
      ),
      GoRoute(
        path: '/invoicing/settings',
        builder: (_, _) => const Scaffold(body: Text('SETTINGS')),
      ),
      GoRoute(
        path: '/invoicing/new',
        builder: (_, _) => const Scaffold(body: Text('NEW')),
      ),
      GoRoute(
        path: '/invoicing/:id',
        builder: (_, s) =>
            Scaffold(body: Text('INVOICE ${s.pathParameters['id']}')),
      ),
      GoRoute(
        path: '/invoicing/:id/edit',
        builder: (_, s) =>
            Scaffold(body: Text('EDIT ${s.pathParameters['id']}')),
      ),
    ],
  );
  addTearDown(router.dispose);
  await tester.pumpWidget(
    ProviderScope(
      retry: noProviderRetry,
      overrides: [
        apiDioProvider.overrideWithValue(dio),
        isOwnerSessionProvider.overrideWithValue(owner),
        appDatabaseProvider.overrideWithValue(_db),
        connectivityServiceProvider.overrideWithValue(_connectivity),
        moduleSyncQueueProvider.overrideWithValue(_queue),
      ],
      child: MaterialApp.router(routerConfig: router),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

/// Builds and shows [finder] (lazy lists build only what is on screen).
Future<void> _show(WidgetTester tester, Finder finder) async {
  if (finder.evaluate().isEmpty) {
    await tester.scrollUntilVisible(
      finder,
      200,
      scrollable: find
          .byWidgetPredicate(
            (w) => w is Scrollable && w.axisDirection == AxisDirection.down,
          )
          .first,
    );
  }
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
}

Future<void> _tap(WidgetTester tester, Finder finder) async {
  await _show(tester, finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Map<String, dynamic> _body(RequestOptions r) =>
    Map<String, dynamic>.from(r.data as Map);

void main() {
  setUpAll(() {
    useSystemSqlite();
    return initializeDateFormatting('fr_FR');
  });

  group('RemoteInvoiceDataSource (15.5 calls)', () {
    late _Server server;
    late RemoteInvoiceDataSource ds;

    setUp(() {
      server = _Server((r) {
        if (r.method == 'GET' && r.uri.path.endsWith('/invoices')) {
          return _json({
            'success': true,
            'data': [_invoice('a')],
            'meta': {'page': 0, 'size': 20, 'totalElements': 41},
          });
        }
        if (r.uri.path.endsWith('/credit-notes')) {
          return _ok({
            'id': 'cn-1',
            'number': 'AV-2026-000001',
            'totalTtc': 5000,
            'lines': [
              {'invoicePosition': 2, 'quantity': 1, 'description': 'x'},
            ],
          });
        }
        return _ok(_invoice('a', status: 'PAID'));
      });
      ds = RemoteInvoiceDataSource(dio: Dio()..httpClientAdapter = server);
    });

    test(
      'list: search trimmed, issue date range as yyyy-MM-dd, total',
      () async {
        final page = await ds.list(
          status: 'PAID',
          search: '  fac-2026-0000 ',
          from: DateTime(2026, 10, 1),
          to: DateTime(2026, 10, 31, 23, 59),
          page: 2,
        );
        final q = server.requests.single.queryParameters;
        expect(q['search'], 'fac-2026-0000');
        expect(q['from'], '2026-10-01');
        expect(q['to'], '2026-10-31');
        expect(q['status'], 'PAID');
        expect(q['page'], 2);
        expect(page.totalElements, 41);
        expect(page.items.single.id, 'a');
      },
    );

    test('list without criteria sends neither search nor dates', () async {
      await ds.list(search: '   ');
      final q = server.requests.single.queryParameters;
      expect(q.containsKey('search'), isFalse);
      expect(q.containsKey('from'), isFalse);
      expect(q.containsKey('status'), isFalse);
    });

    test('pay back-dated: paidAt = the chosen day, method trimmed', () async {
      final paid = await ds.pay(
        'a',
        paymentMethod: ' Espèces ',
        paidOn: DateTime(2026, 10, 30),
      );
      final r = server.where('POST', '/a/pay').single;
      expect(_body(r), {'paymentMethod': 'Espèces', 'paidAt': '2026-10-30'});
      expect(paid.status, 'PAID');
    });

    test('send with an edited phone; credit note per line', () async {
      await ds.send('a', phone: ' +237699000000 ');
      expect(_body(server.where('POST', '/a/send').single), {
        'phone': '+237699000000',
      });
      final note = await ds.createCreditNote(
        'a',
        reason: ' Retour ',
        lines: [(position: 2, quantity: 1)],
      );
      expect(_body(server.where('POST', '/a/credit-notes').single), {
        'reason': 'Retour',
        'lines': [
          {'position': 2, 'quantity': 1},
        ],
      });
      expect(note.number, 'AV-2026-000001');
      // full credit note: no `lines`
      await ds.createCreditNote('a', reason: 'Tout');
      expect(
        _body(
          server.where('POST', '/a/credit-notes').last,
        ).containsKey('lines'),
        isFalse,
      );
    });
  });

  group('InvoiceListPage — the device copy, search, filters (15.5b)', () {
    String two(int n) => n.toString().padLeft(2, '0');
    String day(DateTime d) => '${d.year}-${two(d.month)}-${two(d.day)}';
    final today = doualaToday();
    Map<String, dynamic> numbered(int n, {String? client, DateTime? issued}) {
      final inv = _invoice(
        'inv-$n',
        number: 'FAC-2026-${n.toString().padLeft(6, '0')}',
        client: client ?? 'Client $n',
      );
      inv['issueDate'] = day(issued ?? today.subtract(Duration(days: 40 - n)));
      inv['createdAt'] = DateTime.utc(
        2026,
        1,
        1,
      ).add(Duration(hours: n)).toIso8601String();
      return inv;
    }

    final many = [
      for (var n = 1; n <= 30; n++)
        numbered(n, client: n == 7 ? 'Boutique Ndolo' : null),
    ];
    ResponseBody noCall(RequestOptions r) =>
        throw StateError('unexpected ${r.method} ${r.uri}');

    testWidgets('read from the device, newest first, no request; no id on '
        'screen', (tester) async {
      final server = await _pump(
        tester,
        const InvoiceListPage(),
        noCall,
        seed: many,
      );
      expect(find.text('FAC-2026-000030'), findsOneWidget);
      expect(find.textContaining('inv-'), findsNothing);
      expect(find.text('Émise'), findsWidgets);
      await tester.scrollUntilVisible(
        find.text('FAC-2026-000001'),
        300,
        scrollable: find.byType(Scrollable).last,
      );
      expect(find.text('FAC-2026-000001'), findsOneWidget);
      expect(server.requests, isEmpty);
    });

    testWidgets('offline: same list; an invoice written offline shows « En '
        'attente de synchronisation », an offline issue « Numéro à venir »', (
      tester,
    ) async {
      final offlineIssued = _invoice('local-1', number: null)
        ..['createdAt'] = DateTime.utc(2026, 12, 1).toIso8601String();
      await _pump(
        tester,
        const InvoiceListPage(),
        noCall,
        online: false,
        seed: many,
      );
      await tester.runAsync(
        () => seedInvoices(_db, [offlineIssued], pending: true),
      );
      await tester.pumpAndSettle();
      expect(find.text('Numéro à venir'), findsOneWidget);
      expect(
        find.textContaining('En attente de synchronisation'),
        findsOneWidget,
      );
      expect(
        find.text('Émise — numéro attribué à la synchronisation'),
        findsOneWidget,
      );
    });

    testWidgets('search (debounced): number or client name', (tester) async {
      await _pump(tester, const InvoiceListPage(), noCall, seed: many);
      await tester.enterText(find.byKey(const Key('invoice-search')), 'ndolo');
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pumpAndSettle();
      expect(find.text('FAC-2026-000007'), findsOneWidget);
      expect(find.text('FAC-2026-000030'), findsNothing);
      await tester.enterText(
        find.byKey(const Key('invoice-search')),
        'fac-2026-000012',
      );
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pumpAndSettle();
      expect(find.text('FAC-2026-000012'), findsOneWidget);
      expect(find.text('FAC-2026-000007'), findsNothing);
    });

    testWidgets('date range: issue days inclusive; clearing the chip shows '
        'everything again', (tester) async {
      final start = today.subtract(const Duration(days: 5));
      String typed(DateTime d) => '${two(d.month)}/${two(d.day)}/${d.year}';
      await _pump(
        tester,
        const InvoiceListPage(),
        noCall,
        seed: [
          numbered(1, issued: today.subtract(const Duration(days: 10))),
          numbered(2, issued: start),
          numbered(3, issued: today),
          _invoice('d-1', status: 'DRAFT'),
        ],
      );
      await tester.scrollUntilVisible(
        find.byKey(const Key('invoice-range')),
        100,
        scrollable: find
            .byWidgetPredicate(
              (w) => w is Scrollable && w.axisDirection == AxisDirection.right,
            )
            .first,
      );
      await tester.ensureVisible(find.byKey(const Key('invoice-range')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('invoice-range')));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Switch to input'));
      await tester.pumpAndSettle();
      final fields = find.byType(TextField);
      await tester.enterText(
        fields.at(fields.evaluate().length - 2),
        typed(start),
      );
      await tester.enterText(fields.last, typed(today));
      await tester.tap(find.text('Valider'));
      await tester.pumpAndSettle();
      expect(
        find.text('${formatDay(start)} – ${formatDay(today)}'),
        findsOneWidget,
      );
      expect(find.text('FAC-2026-000002'), findsOneWidget);
      expect(find.text('FAC-2026-000003'), findsOneWidget);
      expect(find.text('FAC-2026-000001'), findsNothing);
      expect(find.text('Brouillon'), findsNothing);

      await tester.ensureVisible(find.byTooltip('Retirer la période'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Retirer la période'));
      await tester.pumpAndSettle();
      expect(find.text('Période'), findsOneWidget);
      expect(find.text('FAC-2026-000001'), findsOneWidget);
      expect(find.text('Brouillon'), findsWidgets);
    });

    testWidgets('status chip; nothing matching → empty state', (tester) async {
      await _pump(tester, const InvoiceListPage(), noCall, seed: many);
      await tester.tap(find.text('Payées'));
      await tester.pumpAndSettle();
      expect(find.text('Aucune facture ne correspond'), findsOneWidget);
    });

    testWidgets('pull to refresh runs a sync cycle', (tester) async {
      await _pump(tester, const InvoiceListPage(), noCall, seed: many);
      await tester.fling(
        find.text('FAC-2026-000030'),
        const Offset(0, 400),
        1000,
      );
      await tester.pumpAndSettle();
      expect(_queue.syncNowCalls, 1);
    });

    testWidgets('employee: no seller settings action, « Nouvelle facture » '
        'kept', (tester) async {
      await _pump(
        tester,
        const InvoiceListPage(),
        noCall,
        owner: false,
        seed: many,
      );
      expect(find.byKey(const Key('invoicing-settings')), findsNothing);
      expect(find.text('Nouvelle facture'), findsOneWidget);
    });
  });

  group('InvoiceFormPage — client picker', () {
    testWidgets('CRM on: suggestions as you type, picked client sent by id', (
      tester,
    ) async {
      final server = await _pump(tester, const InvoiceFormPage(), (r) {
        if (r.uri.path.endsWith('/clients')) {
          final search = r.queryParameters['search'] as String?;
          return _ok({
            'crmAvailable': true,
            'clients': search == null
                ? []
                : [
                    {
                      'id': 'crm-9',
                      'name': 'Awa Traoré',
                      'phone': '+237690000009',
                    },
                  ],
          });
        }
        if (r.method == 'POST') return _ok(_invoice('new-1', status: 'DRAFT'));
        throw StateError('unexpected ${r.method} ${r.uri}');
      });
      await tester.enterText(
        find.byKey(const Key('invoice-client-name')),
        'Awa',
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(server.requests.last.queryParameters['search'], 'Awa');
      await tester.tap(find.text('Awa Traoré'));
      await tester.pumpAndSettle();
      expect(find.text('+237690000009'), findsOneWidget);

      await tester.enterText(
        find.byKey(const Key('invoice-line-0-description')),
        'Sac de riz',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-quantity')),
        '2',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-price')),
        '7500',
      );
      await tester.pump();
      await _show(tester, find.byKey(const Key('invoice-form-total')));
      expect(find.text(formatXaf(15000)), findsOneWidget);
      await _tap(tester, find.byKey(const Key('invoice-save')));

      final body = _body(server.where('POST', '/invoices').single);
      expect(body['client'], containsPair('id', 'crm-9'));
      expect(body['client'], containsPair('name', 'Awa Traoré'));
      expect(body['lines'], [
        {
          'description': 'Sac de riz',
          'quantity': 2,
          'unitPrice': 7500,
          'vatTreatment': 'STANDARD',
        },
      ]);
      expect(find.text('INVOICE new-1'), findsOneWidget);
    });

    testWidgets('CRM off: free-text name and phone, no suggestion list', (
      tester,
    ) async {
      final server = await _pump(tester, const InvoiceFormPage(), (r) {
        if (r.uri.path.endsWith('/clients')) {
          return _ok({'crmAvailable': false, 'clients': []});
        }
        return _ok(_invoice('new-2', status: 'DRAFT'));
      });
      await tester.enterText(
        find.byKey(const Key('invoice-client-name')),
        'Société Ndolo',
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.textContaining('Aucun client trouvé'), findsNothing);
      await tester.enterText(
        find.byKey(const Key('invoice-client-phone')),
        '+237690000002',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-description')),
        'Pagne',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-price')),
        '5000',
      );
      await _tap(tester, find.byKey(const Key('invoice-save')));
      final client = _body(server.where('POST', '/invoices').single)['client'];
      expect(client['id'], isNull);
      expect(client['name'], 'Société Ndolo');
      expect(client['phone'], '+237690000002');
    });

    testWidgets('free-text phone: 9 local digits stored +237…; an invalid '
        'number refused in French before any call', (tester) async {
      final server = await _pump(tester, const InvoiceFormPage(), (r) {
        if (r.uri.path.endsWith('/clients')) {
          return _ok({'crmAvailable': false, 'clients': []});
        }
        return _ok(_invoice('new-3', status: 'DRAFT'));
      });
      await tester.enterText(
        find.byKey(const Key('invoice-client-name')),
        'Paul',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-description')),
        'Pagne',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-price')),
        '5000',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-client-phone')),
        '12',
      );
      await _tap(tester, find.byKey(const Key('invoice-save')));
      expect(
        find.text('Numéro invalide. Exemple : 6 99 11 22 33'),
        findsOneWidget,
      );
      expect(server.where('POST', '/invoices'), isEmpty);

      await tester.enterText(
        find.byKey(const Key('invoice-client-phone')),
        '6 99 00 01 56',
      );
      await _tap(tester, find.byKey(const Key('invoice-save')));
      final client = _body(server.where('POST', '/invoices').single)['client'];
      expect(client['phone'], '+237699000156');
    });

    testWidgets('CRM known, then offline: « Hors ligne : le nom saisi sera '
        'utilisé. », no request', (tester) async {
      final server = await _pump(tester, const InvoiceFormPage(), (r) {
        if (r.uri.path.endsWith('/clients')) {
          return _ok({'crmAvailable': true, 'clients': []});
        }
        throw StateError('unexpected ${r.uri}');
      });
      final before = server.requests.length;
      _connectivity.online = false;
      await tester.enterText(
        find.byKey(const Key('invoice-client-name')),
        'Awa',
      );
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(
        find.text('Hors ligne : le nom saisi sera utilisé.'),
        findsOneWidget,
      );
      expect(find.textContaining('Aucun client trouvé'), findsNothing);
      expect(server.requests.length, before);
    });

    testWidgets('unknown CRM client → the server French message', (
      tester,
    ) async {
      await _pump(tester, const InvoiceFormPage(), (r) {
        if (r.uri.path.endsWith('/clients')) {
          return _ok({'crmAvailable': false, 'clients': []});
        }
        return _error(404, 'Client introuvable', 'CLIENT_NOT_FOUND');
      });
      await tester.enterText(find.byKey(const Key('invoice-client-name')), 'X');
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-description')),
        'Pagne',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-price')),
        '5000',
      );
      await _tap(tester, find.byKey(const Key('invoice-save')));
      expect(find.text('Client introuvable'), findsOneWidget);
    });

    testWidgets('required fields checked before any call', (tester) async {
      final server = await _pump(
        tester,
        const InvoiceFormPage(),
        (_) => _ok({'crmAvailable': false, 'clients': []}),
      );
      await _tap(tester, find.byKey(const Key('invoice-save')));
      expect(find.text('Le nom du client est requis'), findsOneWidget);
      expect(find.text('La désignation est requise'), findsOneWidget);
      expect(server.where('POST', '/invoices'), isEmpty);
    });
  });

  group('InvoiceDetailPage — actions', () {
    testWidgets('issue with warnings: listed first, owner may open the '
        'settings, or issue anyway', (tester) async {
      final warnings = ['Le NIU de votre commerce n\'est pas renseigné'];
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'd-1'),
        (r) => r.method == 'POST'
            ? _ok(_invoice('d-1'))
            : _ok(_invoice('d-1', status: 'DRAFT', warnings: warnings)),
      );
      expect(find.text('Facture (brouillon)'), findsOneWidget);
      await _tap(tester, find.byKey(const Key('invoice-issue')));
      expect(find.byKey(const Key('invoice-issue-dialog')), findsOneWidget);
      expect(find.text('• ${warnings.single}'), findsWidgets);
      await tester.tap(find.byKey(const Key('invoice-issue-settings')));
      await tester.pumpAndSettle();
      expect(find.text('SETTINGS'), findsOneWidget);
      expect(server.where('POST', '/issue'), isEmpty);

      // back on the invoice: issue anyway
      GoRouter.of(tester.element(find.text('SETTINGS'))).pop();
      await tester.pumpAndSettle();
      await _tap(tester, find.byKey(const Key('invoice-issue')));
      await tester.tap(find.byKey(const Key('invoice-issue-confirm')));
      await tester.pumpAndSettle();
      expect(server.where('POST', '/d-1/issue'), hasLength(1));
      expect(find.text('Facture n° FAC-2026-000007 émise'), findsOneWidget);
    });

    testWidgets('employee: issue confirm without « Compléter mes '
        'informations »; no cancel / credit note', (tester) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'd-1'),
        (_) => _ok(_invoice('d-1', status: 'DRAFT', warnings: ['NIU'])),
        owner: false,
      );
      await _tap(tester, find.byKey(const Key('invoice-issue')));
      expect(find.byKey(const Key('invoice-issue-settings')), findsNothing);
      expect(find.text('Émettre quand même'), findsOneWidget);
    });

    testWidgets('pay: today (Douala) by default → no `paidAt` sent; a method '
        'must be chosen; PAID shown with its date', (tester) async {
      var paid = false;
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (r) {
          if (r.method == 'POST') paid = true;
          return _ok(
            paid
                ? _invoice(
                    'i-1',
                    status: 'PAID',
                    paidAt: DateTime(
                      2026,
                      10,
                      29,
                      12,
                    ).toUtc().toIso8601String(),
                  )
                : _invoice('i-1'),
          );
        },
      );
      await _tap(tester, find.byKey(const Key('invoice-pay')));
      expect(find.text('Aujourd\'hui'), findsOneWidget);
      // no method preselected: « Enregistrer » disabled until one is chosen
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('pay-confirm')))
            .onPressed,
        isNull,
      );
      await tester.tap(find.text('Mobile Money'));
      await tester.pump();
      await tester.tap(find.byKey(const Key('pay-confirm')));
      await tester.pumpAndSettle();
      expect(_body(server.where('POST', '/i-1/pay').single), {
        'paymentMethod': 'Mobile Money',
      });
      expect(find.text('Payée'), findsOneWidget);
      expect(find.byKey(const Key('invoice-paid')), findsOneWidget);
      expect(find.text('Payée le 29 oct. 2026 — Espèces'), findsOneWidget);
    });

    testWidgets('pay: yesterday picked in the date picker → `paidAt` = '
        'yesterday', (tester) async {
      final yesterday = doualaToday().subtract(const Duration(days: 1));
      String two(int n) => n.toString().padLeft(2, '0');
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (_) => _ok(_invoice('i-1')),
      );
      await _tap(tester, find.byKey(const Key('invoice-pay')));
      await tester.tap(find.byKey(const Key('pay-date')));
      await tester.pumpAndSettle();
      // text entry mode of the picker (default en_US format in tests)
      await tester.tap(find.byTooltip('Switch to input'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byType(TextField).last,
        '${two(yesterday.month)}/${two(yesterday.day)}/${yesterday.year}',
      );
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      expect(find.text(formatDay(yesterday)), findsOneWidget);
      await tester.tap(find.text('Espèces'));
      await tester.pump();
      await tester.tap(find.byKey(const Key('pay-confirm')));
      await tester.pumpAndSettle();
      expect(_body(server.where('POST', '/i-1/pay').single), {
        'paymentMethod': 'Espèces',
        'paidAt': RemoteInvoiceDataSource.isoDay(yesterday),
      });
    });

    testWidgets('pay refused (date before issue) → server French message', (
      tester,
    ) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (r) => r.method == 'POST'
            ? _error(
                400,
                'Date de paiement invalide : elle ne peut pas précéder la '
                    'date d\'émission de la facture',
                'INVOICE_PAYMENT_DATE_INVALID',
              )
            : _ok(_invoice('i-1')),
      );
      await _tap(tester, find.byKey(const Key('invoice-pay')));
      await tester.tap(find.text('Espèces'));
      await tester.pump();
      await tester.tap(find.byKey(const Key('pay-confirm')));
      await tester.pumpAndSettle();
      expect(find.textContaining('Date de paiement invalide'), findsOneWidget);
    });

    testWidgets('send: client phone prefilled and editable → « Facture '
        'envoyée », send count shown', (tester) async {
      var sent = false;
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (r) {
          if (r.method == 'POST') sent = true;
          return _ok(_invoice('i-1', sendCount: sent ? 1 : 0));
        },
      );
      await _tap(tester, find.byKey(const Key('invoice-send')));
      final field = tester.widget<TextField>(
        find.byKey(const Key('send-phone')),
      );
      expect(field.controller!.text, '+237690000001');
      await tester.enterText(
        find.byKey(const Key('send-phone')),
        '+237699999999',
      );
      await tester.tap(find.byKey(const Key('send-confirm')));
      await tester.pumpAndSettle();
      expect(_body(server.where('POST', '/i-1/send').single), {
        'phone': '+237699999999',
      });
      expect(find.text('Facture envoyée'), findsOneWidget);
      expect(find.byKey(const Key('invoice-sent')), findsOneWidget);
      expect(find.textContaining('Envoyée 1 fois'), findsOneWidget);
    });

    testWidgets('send: provider error → French message', (tester) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (r) => r.method == 'POST'
            ? _error(
                503,
                'L\'envoi WhatsApp n\'est pas disponible pour le moment',
                'WHATSAPP_UNAVAILABLE',
              )
            : _ok(_invoice('i-1')),
      );
      await _tap(tester, find.byKey(const Key('invoice-send')));
      await tester.tap(find.byKey(const Key('send-confirm')));
      await tester.pumpAndSettle();
      expect(
        find.text('L\'envoi WhatsApp n\'est pas disponible pour le moment'),
        findsOneWidget,
      );
    });

    final threeLines = [
      for (var p = 1; p <= 3; p++)
        {
          'position': p,
          'description': 'Article $p',
          'quantity': 3,
          'unitPrice': 1000,
          'vatTreatment': 'STANDARD',
          'totalTtc': 3000,
        },
    ];

    testWidgets('credit note per line: remaining quantities from the '
        'existing credit notes; 1 of 3 units on line 2 → created, remaining '
        'updated', (tester) async {
      var created = false;
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'p-1'),
        (r) {
          final path = r.uri.path;
          if (path.endsWith('/credit-notes/cn-1')) {
            return _ok({
              'id': 'cn-1',
              'number': 'AV-2026-000001',
              'totalTtc': 2000,
              'lines': [
                {'invoicePosition': 2, 'quantity': 2, 'description': 'A2'},
              ],
            });
          }
          if (r.method == 'POST') {
            created = true;
            return _ok({
              'id': 'cn-2',
              'number': 'AV-2026-000002',
              'totalTtc': 1000,
              'lines': [],
            });
          }
          return _ok(
            _invoice(
              'p-1',
              status: 'PAID',
              total: 9000,
              lines: threeLines,
              paidAt: '2026-10-21T10:00:00Z',
              creditedTtc: created ? 3000 : 2000,
              creditNotes: [
                {
                  'id': 'cn-1',
                  'number': 'AV-2026-000001',
                  'issuedAt': '2026-10-22T10:00:00Z',
                  'totalTtc': 2000,
                  'reason': 'Retour',
                },
                if (created)
                  {
                    'id': 'cn-2',
                    'number': 'AV-2026-000002',
                    'issuedAt': '2026-10-30T10:00:00Z',
                    'totalTtc': 1000,
                    'reason': 'Casse',
                  },
              ],
            ),
          );
        },
      );
      expect(find.text('AV-2026-000001'), findsOneWidget);
      await _show(tester, find.byKey(const Key('invoice-remaining')));
      expect(
        tester.widget<Text>(find.byKey(const Key('invoice-remaining'))).data,
        formatXaf(7000),
      );
      await _tap(tester, find.byKey(const Key('invoice-credit-note')));
      await tester.tap(find.text('Par ligne'));
      await tester.pumpAndSettle();
      expect(find.text('Reste 1 sur 3'), findsOneWidget); // line 2
      expect(find.text('Reste 3 sur 3'), findsNWidgets(2));

      // more than what remains → refused locally
      await tester.enterText(find.byKey(const Key('credit-note-line-2')), '2');
      await tester.enterText(
        find.byKey(const Key('credit-note-reason')),
        'Casse',
      );
      await _tap(tester, find.byKey(const Key('credit-note-submit')));
      expect(find.text('Au plus 1 pour « Article 2 »'), findsOneWidget);
      expect(server.where('POST', '/credit-notes'), isEmpty);

      await tester.enterText(find.byKey(const Key('credit-note-line-2')), '1');
      await _tap(tester, find.byKey(const Key('credit-note-submit')));
      expect(_body(server.where('POST', '/p-1/credit-notes').single), {
        'reason': 'Casse',
        'lines': [
          {'position': 2, 'quantity': 1},
        ],
      });
      expect(find.text('Avoir AV-2026-000002 créé'), findsOneWidget);
      await _show(tester, find.byKey(const Key('invoice-remaining')));
      expect(find.text('AV-2026-000002'), findsOneWidget);
      expect(
        tester.widget<Text>(find.byKey(const Key('invoice-remaining'))).data,
        formatXaf(6000),
      );
    });

    testWidgets('credit note: reason required; server quantity refusal shown', (
      tester,
    ) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'p-1'),
        (r) => r.method == 'POST'
            ? _error(
                422,
                'L\'avoir n\'est pas valide : la quantité dépasse ce qui reste',
                'CREDIT_NOTE_INVALID',
              )
            : _ok(_invoice('p-1', status: 'PAID')),
      );
      await _tap(tester, find.byKey(const Key('invoice-credit-note')));
      await _tap(tester, find.byKey(const Key('credit-note-submit')));
      expect(find.text('Le motif de l\'avoir est requis'), findsOneWidget);
      await tester.enterText(
        find.byKey(const Key('credit-note-reason')),
        'Erreur',
      );
      await _tap(tester, find.byKey(const Key('credit-note-submit')));
      expect(find.textContaining('la quantité dépasse'), findsOneWidget);
    });

    testWidgets('badges « Remplacée » and « Entièrement créditée »; no '
        'credit note action once fully credited', (tester) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'p-1'),
        (_) => _ok(
          _invoice(
            'p-1',
            status: 'PAID',
            supersededAt: '2026-10-25T10:00:00Z',
            fullyCredited: true,
            creditedTtc: 15000,
          ),
        ),
      );
      expect(find.text('Remplacée'), findsOneWidget);
      expect(find.text('Entièrement créditée'), findsOneWidget);
      expect(find.byKey(const Key('invoice-credit-note')), findsNothing);
    });

    testWidgets('owner: cancel (reason) on an issued invoice', (tester) async {
      var cancelled = false;
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (r) {
          if (r.method == 'POST') cancelled = true;
          return _ok(
            _invoice('i-1', status: cancelled ? 'CANCELLED' : 'ISSUED'),
          );
        },
      );
      await _tap(tester, find.byKey(const Key('invoice-cancel')));
      await tester.enterText(find.byKey(const Key('reason-field')), 'Erreur');
      await tester.tap(find.byKey(const Key('reason-confirm')));
      await tester.pumpAndSettle();
      expect(_body(server.where('POST', '/i-1/cancel').single), {
        'reason': 'Erreur',
      });
      expect(find.text('Annulée'), findsOneWidget);
      expect(find.byKey(const Key('invoice-send')), findsNothing);
    });

    testWidgets('employee: no cancel, no credit note; a 403 renders the '
        'French message', (tester) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (r) => r.method == 'POST'
            ? _error(
                403,
                'Vous n\'avez pas les droits nécessaires',
                'FORBIDDEN',
              )
            : _ok(_invoice('i-1')),
        owner: false,
      );
      expect(find.byKey(const Key('invoice-cancel')), findsNothing);
      expect(find.byKey(const Key('invoice-credit-note')), findsNothing);
      expect(find.byKey(const Key('invoice-send')), findsOneWidget);
      await _tap(tester, find.byKey(const Key('invoice-send')));
      await tester.tap(find.byKey(const Key('send-confirm')));
      await tester.pumpAndSettle();
      expect(
        find.text('Vous n\'avez pas les droits nécessaires'),
        findsOneWidget,
      );
    });

    testWidgets('draft: edit and delete', (tester) async {
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'd-1'),
        (r) => r.method == 'DELETE'
            ? ResponseBody.fromString('', 204)
            : _ok(_invoice('d-1', status: 'DRAFT')),
      );
      await _tap(tester, find.byKey(const Key('invoice-edit')));
      expect(find.text('EDIT d-1'), findsOneWidget);
      GoRouter.of(tester.element(find.text('EDIT d-1'))).pop();
      await tester.pumpAndSettle();
      await _tap(tester, find.byKey(const Key('invoice-delete')));
      await tester.tap(find.text('Supprimer'));
      await tester.pumpAndSettle();
      expect(server.where('DELETE', '/d-1'), hasLength(1));
      expect(find.text('LIST'), findsOneWidget);
    });
  });

  group('Offline (15.5b)', () {
    ResponseBody offline(RequestOptions r) =>
        throw DioException.connectionError(requestOptions: r, reason: 'off');

    testWidgets('issue offline: « Émise — numéro attribué à la '
        'synchronisation », pending, ISSUE_INVOICE queued, no call', (
      tester,
    ) async {
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'd-1'),
        offline,
        online: false,
        seed: [_invoice('d-1', status: 'DRAFT')],
      );
      expect(find.text('Facture (brouillon)'), findsOneWidget);
      await _tap(tester, find.byKey(const Key('invoice-issue')));
      expect(
        find.textContaining('Conformité vérifiée à la synchronisation'),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('invoice-issue-confirm')));
      await tester.pumpAndSettle();
      expect(
        find.text('Facture émise — numéro attribué à la synchronisation'),
        findsOneWidget,
      );
      expect(
        find.text('Émise — numéro attribué à la synchronisation'),
        findsOneWidget,
      );
      expect(find.byKey(const Key('invoice-pending')), findsOneWidget);
      expect(_queue.operations, ['ISSUE_INVOICE']);
      expect(server.requests, isEmpty);
    });

    testWidgets('pay offline: « Payée », queued with the method', (
      tester,
    ) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        offline,
        online: false,
        seed: [_invoice('i-1')],
      );
      await _tap(tester, find.byKey(const Key('invoice-pay')));
      await tester.tap(find.text('Espèces'));
      await tester.pump();
      await tester.tap(find.byKey(const Key('pay-confirm')));
      await tester.pumpAndSettle();
      expect(
        find.text('Paiement enregistré — en attente de synchronisation'),
        findsOneWidget,
      );
      expect(find.text('Payée'), findsOneWidget);
      expect(_queue.queued.single.operation, 'PAY_INVOICE');
      expect(_queue.queued.single.payload['paymentMethod'], 'Espèces');
    });

    testWidgets('PDF and WhatsApp offline: « Disponible une fois connecté »', (
      tester,
    ) async {
      final server = await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        offline,
        online: false,
        seed: [_invoice('i-1')],
      );
      await _tap(tester, find.text('Voir le PDF'));
      expect(find.text('Disponible une fois connecté'), findsOneWidget);
      ScaffoldMessenger.of(
        tester.element(find.byType(InvoiceDetailPage)),
      ).clearSnackBars();
      await tester.pumpAndSettle();
      await _tap(tester, find.byKey(const Key('invoice-send')));
      expect(find.byKey(const Key('send-phone')), findsNothing);
      expect(find.text('Disponible une fois connecté'), findsOneWidget);
      expect(server.requests, isEmpty);
    });

    testWidgets('online, but changes still queued: PDF « Disponible une fois '
        'la facture synchronisée », no server read', (tester) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'local-1'),
        (r) => throw StateError('unexpected ${r.uri}'),
        seed: [_invoice('local-1', number: null)],
      );
      // the offline issue of local-1 is still queued
      _queue.queued.add((
        operation: 'ISSUE_INVOICE',
        payload: const {'invoiceId': 'local-1'},
        entityId: 'local-1',
      ));
      await _tap(tester, find.text('Voir le PDF'));
      expect(
        find.text('Disponible une fois la facture synchronisée'),
        findsOneWidget,
      );
    });

    testWidgets('create offline: saved on the device, CREATE_INVOICE '
        'queued, the invoice opened', (tester) async {
      final server = await _pump(
        tester,
        const InvoiceFormPage(),
        offline,
        online: false,
      );
      await tester.enterText(
        find.byKey(const Key('invoice-client-name')),
        'Société Ndolo',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-description')),
        'Pagne',
      );
      await tester.enterText(
        find.byKey(const Key('invoice-line-0-price')),
        '5000',
      );
      await _tap(tester, find.byKey(const Key('invoice-save')));
      final op = _queue.queued.single;
      expect(op.operation, 'CREATE_INVOICE');
      expect(find.text('INVOICE ${op.entityId}'), findsOneWidget);
      expect(server.where('POST', '/invoices'), isEmpty);
    });
  });

  group('Local copy first (15.5b review)', () {
    testWidgets('online but the server fails: the device copy is shown, no '
        'error screen', (tester) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (_) => _error(500, 'Erreur', 'INTERNAL_ERROR'),
        seed: [_invoice('i-1')],
      );
      expect(find.text('Facture n° FAC-2026-000007'), findsOneWidget);
      expect(find.byKey(const Key('invoice-pay')), findsOneWidget);
    });

    testWidgets('online: the server copy replaces the device one', (
      tester,
    ) async {
      await _pump(
        tester,
        const InvoiceDetailPage(invoiceId: 'i-1'),
        (_) => _ok(_invoice('i-1', sendCount: 2)),
        seed: [_invoice('i-1')],
      );
      expect(find.textContaining('Envoyée 2 fois'), findsOneWidget);
    });
  });

  group('InvoicingSettingsPage', () {
    final settings = {
      'legalName': 'Boutique Awa',
      'address': null,
      'phone': null,
      'niu': null,
      'rccm': null,
      'vatRegistered': true,
      'vatRate': 19.25,
      'pricesIncludeVat': true,
      'paymentDetails': null,
      'footer': null,
    };

    testWidgets('loads, saves the whole profile; server messages per field', (
      tester,
    ) async {
      final server = await _pump(
        tester,
        const InvoicingSettingsPage(),
        (r) => r.method == 'PUT'
            ? _error(422, 'Validation failed', 'VALIDATION_ERROR', {
                'niu': 'Le NIU ne doit pas dépasser 30 caractères',
                'vatRateConsistent':
                    'Un commerce assujetti à la TVA doit avoir un taux de TVA '
                    'supérieur à 0',
              })
            : _ok(settings),
      );
      expect(find.text('Boutique Awa'), findsOneWidget);
      expect(find.text('19,25'), findsOneWidget);
      await tester.enterText(
        find.byKey(const Key('invoicing-settings-niu')),
        'M0123',
      );
      await tester.enterText(
        find.byKey(const Key('invoicing-settings-vatRate')),
        '0',
      );
      await _tap(tester, find.byKey(const Key('invoicing-settings-save')));
      final body = _body(server.where('PUT', '/invoicing/settings').single);
      expect(body['niu'], 'M0123');
      expect(body['vatRate'], 0);
      expect(body['legalName'], 'Boutique Awa');
      expect(body['vatRegistered'], isTrue);
      expect(
        find.text('Le NIU ne doit pas dépasser 30 caractères'),
        findsOneWidget,
      );
      expect(find.textContaining('taux de TVA supérieur à 0'), findsOneWidget);
      expect(find.text('Validation failed'), findsNothing);
    });

    testWidgets('employee: read only, no save button', (tester) async {
      await _pump(
        tester,
        const InvoicingSettingsPage(),
        (_) => _ok(settings),
        owner: false,
      );
      expect(find.byKey(const Key('invoicing-settings-save')), findsNothing);
      expect(
        find.byKey(const Key('invoicing-settings-read-only')),
        findsOneWidget,
      );
    });
  });

  test('InvoicingSettings JSON round trip keeps blanks as null', () {
    final s = InvoicingSettings.fromJson(const {
      'legalName': ' Awa ',
      'vatRegistered': false,
      'vatRate': 0,
      'pricesIncludeVat': false,
    });
    expect(s.toJson(), containsPair('legalName', 'Awa'));
    expect(s.toJson(), containsPair('niu', null));
    expect(s.toJson(), containsPair('pricesIncludeVat', false));
  });
}
