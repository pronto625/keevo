import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/features/export/data/local/local_export_reader.dart';
import 'package:keevo/features/export/data/local/local_export_service.dart';
import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';
import 'package:keevo/features/export/presentation/page/local_export_page.dart';
import 'package:keevo/features/export/presentation/provider/export_providers.dart';
import 'package:keevo/features/export/presentation/provider/local_export_providers.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// Records the requested exports; scripted outcome.
class _FakeService implements LocalExportService {
  final calls = <({Set<String> datasets, LocalExportFilter filter})>[];
  Future<File> Function(void Function(int)? onProgress)? onExport;

  @override
  int get pageSize => 500;

  @override
  Future<File> export({
    required Set<String> datasets,
    required LocalExportFilter filter,
    required Directory tempDir,
    void Function(int rows)? onProgress,
    DateTime? now,
  }) {
    calls.add((datasets: datasets, filter: filter));
    return onExport?.call(onProgress) ??
        Future.error(ExportException.localFailed);
  }
}

/// v1s-18-5 — « Données de cet appareil » screen.
void main() {
  late _FakeService service;
  late List<File> shared;
  late Map<String, int> counts;

  setUp(() {
    service = _FakeService();
    shared = [];
    counts = {'products': 3, 'sales': 12, 'stores': 1, 'reports': 0};
  });

  Widget app({ExportPrefill? prefill}) => ProviderScope(
        retry: noProviderRetry,
        overrides: [
          localExportServiceProvider.overrideWithValue(service),
          localExportCountsProvider.overrideWith((_) async => counts),
          exportTempDirProvider.overrideWith((_) async => Directory.systemTemp),
          exportFileSharerProvider
              .overrideWithValue((f) async => shared.add(f)),
          exportStoreOptionsProvider.overrideWith((_) async => const [
                ExportStoreOption('s-1', 'Boutique Centre'),
              ]),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: '/exports/local',
            initialExtra: prefill,
            routes: [
              GoRoute(
                path: '/exports/local',
                builder: (_, state) =>
                    LocalExportPage(prefill: state.extra as ExportPrefill?),
              ),
            ],
          ),
        ),
      );

  Future<void> pumpApp(WidgetTester tester, Widget w) async {
    tester.view.physicalSize = const Size(800, 2600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(w);
    await tester.pumpAndSettle();
  }

  Future<void> tapKey(WidgetTester tester, String key) async {
    final f = find.byKey(Key(key));
    await tester.ensureVisible(f);
    await tester.pumpAndSettle();
    await tester.tap(f);
    await tester.pumpAndSettle();
  }

  ButtonStyleButton submit(WidgetTester tester) => tester
      .widget<ButtonStyleButton>(find.byKey(const Key('local-export-submit')));

  CheckboxListTile tile(WidgetTester tester, String key) =>
      tester.widget<CheckboxListTile>(find.byKey(Key(key)));

  testWidgets('scope note, CSV chip, counts, empty dataset disabled',
      (tester) async {
    await pumpApp(tester, app());
    expect(find.text('Données de cet appareil'), findsOneWidget);
    expect(find.byKey(const Key('local-export-scope-note')), findsOneWidget);
    expect(
        find.textContaining('Export généré sur ce téléphone, sans connexion.'),
        findsOneWidget);
    expect(find.text('CSV'), findsOneWidget);
    expect(find.text('3 lignes'), findsOneWidget);
    expect(find.text('1 ligne'), findsOneWidget);
    final reports = tile(tester, 'local-export-dataset-reports');
    expect(reports.enabled, isFalse);
    expect(reports.onChanged, isNull);
    expect(find.text('Aucune donnée sur cet appareil'), findsWidgets);
    expect(submit(tester).onPressed, isNull); // nothing selected
    expect(find.text('Générer le CSV'), findsOneWidget);
  });

  testWidgets('Tout selects every available dataset only', (tester) async {
    service.onExport = (_) async => File('/tmp/x.zip');
    await pumpApp(tester, app());
    await tapKey(tester, 'local-export-all');
    expect(tile(tester, 'local-export-dataset-products').value, isTrue);
    expect(tile(tester, 'local-export-dataset-reports').value, isFalse);
    await tapKey(tester, 'local-export-submit');
    expect(service.calls.single.datasets, {'products', 'sales', 'stores'});
  });

  testWidgets('prefill applied: datasets, store, period', (tester) async {
    final from = DateTime(2026, 10, 1);
    final to = DateTime(2026, 10, 7, 23, 59, 59);
    service.onExport = (_) async => File('/tmp/x.csv');
    await pumpApp(
        tester,
        app(
            prefill: ExportPrefill(
                datasets: const {'sales', 'reports', 'nope'},
                storeId: 's-1',
                from: from,
                to: to)));
    expect(tile(tester, 'local-export-dataset-sales').value, isTrue);
    expect(find.text('Boutique Centre'), findsOneWidget);
    await tapKey(tester, 'local-export-submit');
    final call = service.calls.single;
    expect(call.datasets, {'sales'}); // empty reports never exported
    expect(call.filter.storeId, 's-1');
    expect(call.filter.from, from);
    expect(call.filter.to, to);
  });

  testWidgets('pickers disabled when no selected dataset supports them',
      (tester) async {
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    final period =
        tester.widget<ListTile>(find.byKey(const Key('local-export-period')));
    expect(period.enabled, isFalse);
    final store =
        tester.widget<ListTile>(find.byKey(const Key('local-export-store')));
    expect(store.enabled, isTrue); // products: store scopes the stock
  });

  testWidgets('submit → progress, sharer, « Fichier prêt »', (tester) async {
    final file = File('/tmp/keevo-donnees-appareil-sales.csv');
    service.onExport = (onProgress) async {
      onProgress?.call(500);
      await Future<void>.delayed(const Duration(milliseconds: 10));
      return file;
    };
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.tap(find.byKey(const Key('local-export-submit')));
    await tester.pump();
    expect(find.text('Génération… 500 lignes'), findsOneWidget);
    await tester.pumpAndSettle();
    expect(shared.single.path, file.path);
    expect(find.text('Fichier prêt'), findsOneWidget);
  });

  testWidgets('storage failure → error card, Réessayer re-runs',
      (tester) async {
    var attempt = 0;
    service.onExport = (onProgress) async {
      attempt++;
      if (attempt == 1) throw ExportException.storage;
      onProgress?.call(1);
      return File('/tmp/ok.csv');
    };
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    await tapKey(tester, 'local-export-submit');
    expect(find.byKey(const Key('local-export-error')), findsOneWidget);
    expect(find.text('Espace de stockage insuffisant ou inaccessible'),
        findsOneWidget);
    expect(shared, isEmpty);

    await tapKey(tester, 'local-export-retry');
    expect(service.calls, hasLength(2));
    expect(find.byKey(const Key('local-export-error')), findsNothing);
    expect(shared.single.path, '/tmp/ok.csv');
  });

  testWidgets('other failure → « Impossible de générer le fichier »',
      (tester) async {
    service.onExport = (_) async => throw StateError('boom');
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    await tapKey(tester, 'local-export-submit');
    expect(find.text('Impossible de générer le fichier'), findsOneWidget);
  });

  testWidgets('filters matching no row: nothing shared, file discarded',
      (tester) async {
    final folder = Directory(
        '${Directory.systemTemp.path}/exports/${DateTime.now().microsecondsSinceEpoch}')
      ..createSync(recursive: true);
    final file = File('${folder.path}/empty.csv')..writeAsStringSync('h');
    service.onExport = (_) async => file; // no page: 0 rows
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const Key('local-export-submit')));
      await Future<void>.delayed(const Duration(milliseconds: 100));
    });
    await tester.pumpAndSettle();
    expect(shared, isEmpty);
    expect(
        find.text('Aucune donnée ne correspond à ces filtres sur cet appareil'),
        findsOneWidget);
    expect(find.text('Fichier prêt'), findsNothing);
    expect(folder.existsSync(), isFalse);
  });

  testWidgets('audit criteria from the prefill: note when audit_log selected',
      (tester) async {
    counts['audit_log'] = 4;
    await pumpApp(
        tester,
        app(
            prefill: const ExportPrefill(
                datasets: {'audit_log'},
                criteria: {'action': 'SALE_CREATED'})));
    expect(find.byKey(const Key('local-export-criteria-note')), findsOneWidget);
    expect(
        find.text('Les critères du journal (employé, action, objet, '
            'recherche) ne s\'appliquent pas à l\'export local : tout le '
            'journal présent sur l\'appareil sera exporté'),
        findsOneWidget);
    await tapKey(tester, 'local-export-dataset-audit_log');
    expect(find.byKey(const Key('local-export-criteria-note')), findsNothing);
  });

  testWidgets('no criteria note without criteria', (tester) async {
    counts['audit_log'] = 4;
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'audit_log'})));
    expect(find.byKey(const Key('local-export-criteria-note')), findsNothing);
  });
}
