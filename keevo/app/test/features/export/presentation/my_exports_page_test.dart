import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/core/widget/app_error_widget.dart';
import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';
import 'package:keevo/features/export/presentation/page/my_exports_page.dart';
import 'package:keevo/features/export/presentation/provider/export_providers.dart';

import '../export_test_helpers.dart';

/// v1s-18-4 — « Mes exports ».
void main() {
  late FakeExportDataSource ds;
  late List<File> shared;
  late bool online;

  setUp(() {
    ds = FakeExportDataSource();
    shared = [];
    online = true;
  });

  final now = DateTime.now();

  ExportJob job(
    String id,
    ExportJobStatus status, {
    List<String> datasets = const ['sales'],
    String format = 'CSV',
    DateTime? expiresAt,
    int? rowCount,
    int? fileSize,
    String? storeId,
  }) =>
      ExportJob(
        id: id,
        status: status,
        format: format,
        datasets: datasets,
        storeId: storeId,
        createdAt: now.subtract(const Duration(minutes: 5)),
        expiresAt: expiresAt,
        rowCount: rowCount,
        fileSize: fileSize,
      );

  File tempFile() =>
      File('${Directory.systemTemp.path}/keevo-my-exports-test.csv')
        ..writeAsStringSync('a;b');

  Widget app({String location = '/exports'}) => ProviderScope(
        overrides: [
          exportDataSourceProvider.overrideWithValue(ds),
          exportTempDirProvider.overrideWith((_) async => Directory.systemTemp),
          exportFileSharerProvider
              .overrideWithValue((f) async => shared.add(f)),
          exportOnlineProvider.overrideWith((_) async => online),
          exportIsPremiumProvider.overrideWith((_) async => true),
          exportStoreOptionsProvider.overrideWith((_) async => const [
                ExportStoreOption('s-1', 'Boutique Centre'),
              ]),
          exportJobsRefreshIntervalProvider
              .overrideWithValue(const Duration(seconds: 1)),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: location,
            routes: [
              GoRoute(
                  path: '/exports/new',
                  builder: (_, __) => const Scaffold(body: Text('NOUVEAU'))),
              GoRoute(
                  path: '/exports', builder: (_, __) => const MyExportsPage()),
              GoRoute(
                path: '/exports/:id',
                builder: (_, state) =>
                    MyExportsPage(highlightId: state.pathParameters['id']),
              ),
            ],
          ),
        ),
      );

  Future<void> pumpApp(WidgetTester tester, Widget w) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(w);
    await tester.pumpAndSettle();
  }

  testWidgets('statuses, labels, READY details and expired READY',
      (tester) async {
    ds.onList = () async => [
          job('r1', ExportJobStatus.ready,
              datasets: ['sales', 'mystery'],
              expiresAt: now.add(const Duration(hours: 20)),
              rowCount: 120,
              fileSize: 2048,
              storeId: 's-1'),
          job('x1', ExportJobStatus.ready,
              expiresAt: now.subtract(const Duration(minutes: 1))),
          job('f1', ExportJobStatus.failed, format: 'XLSX'),
          job('e1', ExportJobStatus.expired),
        ];
    await pumpApp(tester, app());

    expect(find.text('Ventes, mystery'), findsOneWidget);
    expect(find.text('Prêt'), findsOneWidget);
    expect(find.text('Expiré'), findsNWidgets(2));
    expect(find.text('Échec'), findsOneWidget);
    expect(find.text('120 lignes · 2 Ko'), findsOneWidget);
    expect(find.textContaining('Expire le '), findsOneWidget);
    expect(find.textContaining('Boutique Centre'), findsOneWidget);
    expect(find.byKey(const Key('export-open-r1')), findsOneWidget);
    expect(find.byKey(const Key('export-open-x1')), findsNothing);
    expect(find.byKey(const Key('export-relaunch-f1')), findsOneWidget);
  });

  testWidgets('« Ouvrir » downloads the file and shares it', (tester) async {
    final file = tempFile();
    ds.onList = () async => [
          job('r1', ExportJobStatus.ready,
              expiresAt: now.add(const Duration(hours: 1))),
        ];
    ds.onDownload = (_) async => file;
    await pumpApp(tester, app());

    await tester.tap(find.byKey(const Key('export-open-r1')));
    await tester.pumpAndSettle();
    expect(ds.downloads, ['r1']);
    expect(shared.single.path, file.path);
  });

  testWidgets('410 on download: message and list refreshed', (tester) async {
    ds.onList = () async => [
          job('r1', ExportJobStatus.ready,
              expiresAt: now.add(const Duration(hours: 1))),
        ];
    ds.onDownload = (_) async => throw ExportException.fromResponse(
        410, {'domainCode': 'EXPORT_EXPIRED'});
    await pumpApp(tester, app());
    final before = ds.listCalls;

    await tester.tap(find.byKey(const Key('export-open-r1')));
    await tester.pumpAndSettle();
    expect(
        find.text('Ce fichier a expiré (conservé 24 h). Relancez l\'export.'),
        findsOneWidget);
    expect(ds.listCalls, greaterThan(before));
    expect(shared, isEmpty);
  });

  testWidgets('FAILED « Relancer » re-posts the same export, no criteria',
      (tester) async {
    ds.onList = () async => [
          ExportJob(
            id: 'f1',
            status: ExportJobStatus.failed,
            format: 'XLSX',
            datasets: const ['sales', 'sale_items'],
            storeId: 's-1',
            from: DateTime(2026, 10, 1),
            to: DateTime(2026, 10, 2),
          ),
        ];
    ds.onRequest = (_) async => const ExportQueued(
        ExportJob(id: 'n1', status: ExportJobStatus.pending, format: 'XLSX'));
    await pumpApp(tester, app());

    await tester.tap(find.byKey(const Key('export-relaunch-f1')));
    await tester.pump();
    await tester.pump();
    final r = ds.requests.single;
    expect(r.datasets, ['sales', 'sale_items']);
    expect(r.format, ExportFormat.xlsx);
    expect(r.storeId, 's-1');
    expect(r.from, DateTime(2026, 10, 1));
    expect(r.criteria, isNull);
    expect(find.textContaining('Export relancé'), findsOneWidget);
  });

  testWidgets('load error → AppErrorWidget, retry reloads', (tester) async {
    var fail = true;
    ds.onList = () async {
      if (fail) throw ExportException.network;
      return const [];
    };
    await pumpApp(tester, app());

    expect(find.byType(AppErrorWidget), findsOneWidget);
    expect(find.text('Impossible de joindre le serveur'), findsOneWidget);
    fail = false;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();
    expect(find.byType(AppErrorWidget), findsNothing);
    expect(find.text('Aucun export en arrière-plan'), findsOneWidget);
  });

  testWidgets('offline → AppErrorWidget, no request', (tester) async {
    online = false;
    await pumpApp(tester, app());
    expect(find.byType(AppErrorWidget), findsOneWidget);
    expect(ds.listCalls, 0);
  });

  testWidgets('/exports/:id highlights the job and opens a READY file once',
      (tester) async {
    final file = tempFile();
    ds.onList = () async => [
          job('other', ExportJobStatus.ready,
              expiresAt: now.add(const Duration(hours: 1))),
          job('r1', ExportJobStatus.ready,
              expiresAt: now.add(const Duration(hours: 1))),
        ];
    ds.onDownload = (_) async => file;
    await pumpApp(tester, app(location: '/exports/r1'));

    expect(ds.downloads, ['r1']);
    expect(shared.single.path, file.path);
    final box =
        tester.widget<Container>(find.byKey(const Key('export-job-r1')));
    final border = (box.decoration as BoxDecoration).border as Border;
    expect(border.top.width, 2);

    // A refresh does not re-open it.
    await tester.drag(find.byType(ListView), const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(ds.downloads, ['r1']);
  });

  testWidgets('auto-refresh while a job is running, stops once done',
      (tester) async {
    var status = ExportJobStatus.running;
    ds.onList = () async => [job('j1', status)];
    await pumpApp(tester, app());
    expect(find.text('En cours'), findsOneWidget);
    final first = ds.listCalls;

    status = ExportJobStatus.pending;
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(ds.listCalls, first + 1);
    expect(find.text('En attente'), findsOneWidget);

    status = ExportJobStatus.ready;
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(find.text('Prêt'), findsOneWidget);
    final settled = ds.listCalls;

    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(ds.listCalls, settled, reason: 'timer cancelled');
  });

  testWidgets('404 on « Ouvrir » refreshes the stale list', (tester) async {
    ds.onList = () async => [
          job('r1', ExportJobStatus.ready,
              expiresAt: now.add(const Duration(hours: 1))),
        ];
    ds.onDownload = (_) async => throw ExportException.fromResponse(
        404, {'domainCode': 'EXPORT_NOT_FOUND'});
    await pumpApp(tester, app());
    final before = ds.listCalls;
    await tester.tap(find.byKey(const Key('export-open-r1')));
    await tester.pumpAndSettle();
    expect(find.text('Export introuvable'), findsOneWidget);
    expect(ds.listCalls, greaterThan(before));
  });

  testWidgets('a failed « Ouvrir » offers « Réessayer » in the SnackBar',
      (tester) async {
    final file = tempFile();
    var fail = true;
    ds.onList = () async => [
          job('r1', ExportJobStatus.ready,
              expiresAt: now.add(const Duration(hours: 1))),
        ];
    ds.onDownload = (_) async {
      if (fail) throw ExportException.network;
      return file;
    };
    await pumpApp(tester, app());
    await tester.tap(find.byKey(const Key('export-open-r1')));
    await tester.pumpAndSettle();
    expect(find.text('Impossible de joindre le serveur'), findsOneWidget);

    fail = false;
    await tester.tap(find.widgetWithText(SnackBarAction, 'Réessayer'));
    await tester.pumpAndSettle();
    expect(ds.downloads, ['r1', 'r1']);
    expect(shared.single.path, file.path);
  });

  testWidgets('/exports/:id unknown → « Export introuvable ou expiré » once',
      (tester) async {
    ds.onList = () async => [job('other', ExportJobStatus.failed)];
    await pumpApp(tester, app(location: '/exports/gone'));
    expect(find.text('Export introuvable ou expiré'), findsOneWidget);
    expect(ds.downloads, isEmpty);
  });
}
