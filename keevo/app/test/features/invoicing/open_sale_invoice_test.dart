import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:keevo/core/extension/extension_actions.dart';
import 'package:keevo/features/export/presentation/provider/export_providers.dart';
import 'package:keevo/features/invoicing/data/remote_invoice_datasource.dart';
import 'package:keevo/features/invoicing/domain/model/invoice.dart';
import 'package:keevo/features/invoicing/presentation/open_sale_invoice.dart';
import 'package:keevo/features/invoicing/presentation/page/invoice_detail_page.dart';
import 'package:keevo/features/invoicing/presentation/provider/invoicing_providers.dart';

const _invoice = Invoice(
  id: 'inv-1',
  number: 'FA-2026-0007',
  status: kInvoiceIssued,
  clientName: 'Awa Traoré',
  totalTtc: 59625,
  saleId: 's-1',
);

/// Answers from closures; counts the lookups.
class _FakeDataSource implements RemoteInvoiceDataSource {
  Future<Invoice?> Function() bySale;
  Future<Invoice> Function() byId;
  int lookups = 0;

  _FakeDataSource({required this.bySale, Future<Invoice> Function()? byId})
      : byId = byId ?? (() async => _invoice);

  @override
  Future<Invoice?> findBySale(String saleId) {
    lookups++;
    return bySale();
  }

  @override
  Future<Invoice> get(String id) => byId();

  @override
  Future<InvoicePdf> pdf(String id, {required String fallbackName}) async =>
      (bytes: const [37, 80, 68, 70], fileName: 'FAC-0007.pdf');
}

DioException _http(int status, Map<String, dynamic> body) {
  final req = RequestOptions(path: '/api/v1/invoices');
  return DioException(
    requestOptions: req,
    type: DioExceptionType.badResponse,
    response: Response(requestOptions: req, statusCode: status, data: body),
  );
}

/// Serves canned answers to a real [Dio].
class _Adapter implements HttpClientAdapter {
  final ResponseBody Function(RequestOptions) handler;
  final List<RequestOptions> requests = [];

  _Adapter(this.handler);

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requests.add(options);
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

ResponseBody _json(Object body, {int status = 200}) => ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      },
    );

void main() {
  setUpAll(() => initializeDateFormatting('fr_FR'));

  group('RemoteInvoiceDataSource', () {
    RemoteInvoiceDataSource ds(_Adapter adapter) =>
        RemoteInvoiceDataSource(dio: Dio()..httpClientAdapter = adapter);

    Map<String, dynamic> dto(String id, {String? supersededAt}) => {
          'id': id,
          'number': 'FA-$id',
          'status': 'ISSUED',
          'client': {'name': 'Awa'},
          'totalTtc': 1000,
          'saleId': 's-1',
          'supersededAt': supersededAt,
        };

    test('?saleId= → the active invoice (a superseded one is skipped)',
        () async {
      final adapter = _Adapter((_) => _json({
            'data': [
              dto('old', supersededAt: '2026-10-30T10:00:00Z'),
              dto('new'),
            ]
          }));
      final found = await ds(adapter).findBySale('s-1');
      expect(found!.id, 'new');
      expect(adapter.requests.single.queryParameters['saleId'], 's-1');
    });

    test('empty page → null (invoice being created)', () async {
      final found =
          await ds(_Adapter((_) => _json({'data': []}))).findBySale('s-1');
      expect(found, isNull);
    });

    test('PDF error body (bytes) decoded for the French message', () async {
      final adapter = _Adapter((_) => _json(
          {'error': 'Facture introuvable', 'domainCode': null},
          status: 404));
      try {
        await ds(adapter).pdf('x', fallbackName: 'f.pdf');
        fail('expected an error');
      } on DioException catch (e) {
        expect(e.response?.data, isA<Map>());
        expect((e.response!.data as Map)['error'], 'Facture introuvable');
      }
    });

    test('only superseded invoices → the most recently created', () async {
      Map<String, dynamic> old(String id, String created) => {
            ...dto(id, supersededAt: '2026-10-30T10:00:00Z'),
            'createdAt': created,
          };
      final found = await ds(_Adapter((_) => _json({
            'data': [
              old('a', '2026-10-01T10:00:00Z'),
              old('c', '2026-10-20T10:00:00Z'),
              old('b', '2026-10-10T10:00:00Z'),
            ]
          }))).findBySale('s-1');
      expect(found!.id, 'c');
    });

    test('empty PDF body → French FormatException', () async {
      final adapter = _Adapter((_) => ResponseBody.fromBytes(const [], 200,
          headers: {
            Headers.contentTypeHeader: ['application/pdf']
          }));
      await expectLater(
          ds(adapter).pdf('x', fallbackName: 'f.pdf'),
          throwsA(isA<FormatException>().having(
              (e) => e.message, 'message', 'Le PDF de la facture est vide')));
    });

    test('invoice without id → French FormatException, not a TypeError', () {
      expect(
          () => Invoice.fromJson({'status': 'ISSUED'}),
          throwsA(isA<FormatException>()
              .having((e) => e.message, 'message', contains('Facture'))));
      expect(() => Invoice.fromJson({'id': 'x', 'saleId': 12}),
          throwsFormatException);
    });

    test('Content-Disposition file name, sanitised', () {
      expect(
          RemoteInvoiceDataSource.fileNameOf('inline; filename=".."'), isNull);
      expect(
          RemoteInvoiceDataSource.fileNameOf('inline; filename="."'), isNull);
      expect(
          RemoteInvoiceDataSource.fileNameOf(
              'inline; filename="FA 2026/0007.pdf"'),
          'FA_2026_0007.pdf');
      expect(RemoteInvoiceDataSource.fileNameOf(null), isNull);
    });
  });

  test('status labels are French, never the raw code', () {
    expect(invoiceStatusLabel('ISSUED'), 'Émise');
    expect(invoiceStatusLabel('PAID'), 'Payée');
    expect(invoiceStatusLabel('DRAFT'), 'Brouillon');
    expect(invoiceStatusLabel('CANCELLED'), 'Annulée');
    expect(invoiceStatusLabel('WHATEVER'), 'Facture');
  });

  group('openSaleInvoice (« Facturer » handler)', () {
    Future<_FakeDataSource> pump(
        WidgetTester tester, Future<Invoice?> Function() bySale) async {
      final ds = _FakeDataSource(bySale: bySale);
      final router = GoRouter(routes: [
        GoRoute(
          path: '/',
          builder: (context, _) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () => openSaleInvoice(context, {'saleId': 's-1'}),
                child: const Text('Facturer'),
              ),
            ),
          ),
        ),
        GoRoute(
          path: '/invoicing/:id',
          builder: (_, state) => Text('INVOICE ${state.pathParameters['id']}'),
        ),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(ProviderScope(
        overrides: [invoiceDataSourceProvider.overrideWithValue(ds)],
        child: MaterialApp.router(routerConfig: router),
      ));
      await tester.tap(find.text('Facturer'));
      await tester.pumpAndSettle();
      return ds;
    }

    testWidgets('invoice exists → its screen opens', (tester) async {
      await pump(tester, () async => _invoice);
      expect(find.text('INVOICE inv-1'), findsOneWidget);
    });

    testWidgets(
        'empty page → « Facture en cours de création », « Réessayer » '
        'opens it once created', (tester) async {
      Invoice? answer;
      final ds = await pump(tester, () async => answer);
      expect(find.text('Facture en cours de création'), findsOneWidget);
      expect(find.text('INVOICE inv-1'), findsNothing);

      answer = _invoice;
      await tester.tap(find.text('Réessayer'));
      await tester.pumpAndSettle();
      expect(ds.lookups, 2);
      expect(find.text('INVOICE inv-1'), findsOneWidget);
    });

    testWidgets('403 → existing French error widget, no technical data',
        (tester) async {
      await pump(
          tester,
          () async => throw _http(403, {
                'error': 'Accès refusé à la facturation',
              }));
      expect(find.text('Un problème est survenu'), findsOneWidget);
      expect(find.text('Accès refusé à la facturation'), findsOneWidget);
      expect(find.textContaining('403'), findsNothing);
    });

    testWidgets('no sale id → nothing happens', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => openSaleInvoice(context, const {}),
            child: const Text('Facturer'),
          ),
        ),
      ));
      await tester.tap(find.text('Facturer'));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsNothing);
    });
  });

  group('InvoiceDetailPage', () {
    Future<List<Invoice>> pump(WidgetTester tester,
        {Future<Invoice> Function()? byId,
        Future<void> Function(Invoice)? opener}) async {
      final opened = <Invoice>[];
      await tester.pumpWidget(ProviderScope(
        overrides: [
          invoiceDataSourceProvider.overrideWithValue(_FakeDataSource(
              bySale: () async => null, byId: byId ?? () async => _invoice)),
          invoicePdfOpenerProvider.overrideWithValue(opener ??
              (i) async {
                opened.add(i);
              }),
        ],
        child: const MaterialApp(home: InvoiceDetailPage(invoiceId: 'inv-1')),
      ));
      await tester.pumpAndSettle();
      return opened;
    }

    testWidgets('number, French status, client, total TTC; « Voir le PDF »',
        (tester) async {
      final opened = await pump(tester);
      expect(find.text('Facture n° FA-2026-0007'), findsOneWidget);
      expect(find.text('Émise'), findsOneWidget);
      expect(find.text('Awa Traoré'), findsOneWidget);
      expect(find.textContaining('59'), findsWidgets);
      expect(find.textContaining('inv-1'), findsNothing);

      await tester.tap(find.text('Voir le PDF'));
      await tester.pumpAndSettle();
      expect(opened.single.id, 'inv-1');
    });

    testWidgets('PDF failure → French snackbar', (tester) async {
      await pump(tester,
          opener: (_) async => throw _http(403, {'error': 'Accès refusé'}));
      await tester.tap(find.text('Voir le PDF'));
      await tester.pumpAndSettle();
      expect(find.text('Accès refusé'), findsOneWidget);
    });

    testWidgets('404 → French error widget', (tester) async {
      await pump(tester,
          byId: () async => throw _http(404, {'error': 'Facture introuvable'}));
      expect(find.text('Facture introuvable'), findsOneWidget);
      expect(find.text('Réessayer'), findsOneWidget);
    });
  });

  testWidgets('production « Facturer » handler (deferred) opens the lookup',
      (tester) async {
    final ds = _FakeDataSource(bySale: () async => null);
    await tester.pumpWidget(ProviderScope(
      overrides: [invoiceDataSourceProvider.overrideWithValue(ds)],
      child: MaterialApp(
        home: Scaffold(
          body: Consumer(
            builder: (context, ref, _) => TextButton(
              onPressed: () => kExtensionActions[kOpenSaleInvoiceAction]!(
                  context, ref, {'saleId': 's-1'}),
              child: const Text('Facturer'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('Facturer'));
    await tester.pumpAndSettle();
    expect(find.byType(SaleInvoiceLookupSheet), findsOneWidget);
    expect(find.text('Facture en cours de création'), findsOneWidget);
    expect(ds.lookups, 1);
  });

  test('invoicePdfOpenerProvider: clears old PDFs, writes and shares',
      () async {
    final temp = await Directory.systemTemp.createTemp('keevo-inv');
    addTearDown(() => temp.delete(recursive: true));
    final stale = File('${temp.path}/invoices/1/old.pdf');
    await stale.create(recursive: true);
    final shared = <File>[];
    final c = ProviderContainer(overrides: [
      invoiceDataSourceProvider
          .overrideWithValue(_FakeDataSource(bySale: () async => null)),
      exportTempDirProvider.overrideWith((ref) async => temp),
      exportFileSharerProvider.overrideWithValue((file) async {
        shared.add(file);
      }),
    ]);
    addTearDown(c.dispose);

    await c.read(invoicePdfOpenerProvider)(_invoice);

    expect(await stale.exists(), isFalse);
    expect(shared.single.uri.pathSegments.last, 'FAC-0007.pdf');
    expect(await shared.single.readAsBytes(), [37, 80, 68, 70]);
  });
}
