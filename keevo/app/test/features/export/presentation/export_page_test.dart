import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';
import 'package:keevo/features/export/presentation/page/export_page.dart';
import 'package:keevo/features/export/presentation/provider/export_providers.dart';

import '../../../helpers/fake_entitlements.dart';
import '../export_test_helpers.dart';

/// v1s-18-4 — « Exporter » screen; v1s-18-3 — PDF templates.
void main() {
  late FakeExportDataSource ds;
  late List<File> shared;
  late bool online;
  late bool? premium;
  ExportPrefill? localPrefill;

  setUp(() {
    ds = FakeExportDataSource();
    shared = [];
    online = true;
    premium = true;
    localPrefill = null;
  });

  Widget app({ExportPrefill? prefill, List<Override> extra = const []}) =>
      ProviderScope(
        overrides: [
          ...extra,
          exportDataSourceProvider.overrideWithValue(ds),
          exportTempDirProvider.overrideWith((_) async => Directory.systemTemp),
          exportFileSharerProvider
              .overrideWithValue((f) async => shared.add(f)),
          exportOnlineProvider.overrideWith((_) async => online),
          exportIsPremiumProvider.overrideWith((_) async => premium),
          exportStoreOptionsProvider.overrideWith((_) async => const [
                ExportStoreOption('s-1', 'Boutique Centre'),
              ]),
        ],
        child: MaterialApp.router(
          routerConfig: GoRouter(
            initialLocation: '/exports/new',
            initialExtra: prefill,
            routes: [
              GoRoute(
                path: '/exports/new',
                builder: (_, state) =>
                    ExportPage(prefill: state.extra as ExportPrefill?),
              ),
              GoRoute(
                  path: '/exports',
                  builder: (_, __) =>
                      const Scaffold(body: Text('MES EXPORTS'))),
              GoRoute(
                  path: '/exports/local',
                  builder: (_, state) {
                    localPrefill = state.extra as ExportPrefill?;
                    return const Scaffold(body: Text('LOCAL CSV'));
                  }),
              GoRoute(
                  path: '/settings/subscription',
                  builder: (_, __) => const Scaffold(body: Text('ABONNEMENT'))),
            ],
          ),
        ),
      );

  /// Tall surface: the whole form is built (ListView is lazy).
  Future<void> pumpApp(WidgetTester tester, Widget w) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(w);
  }

  Future<void> tapKey(WidgetTester tester, String key) async {
    final f = find.byKey(Key(key));
    await tester.ensureVisible(f);
    await tester.pumpAndSettle();
    await tester.tap(f);
    await tester.pumpAndSettle();
  }

  FilledButton submit(WidgetTester tester) =>
      tester.widget<FilledButton>(find.byKey(const Key('export-submit')));

  File tempFile() {
    final f = File('${Directory.systemTemp.path}/keevo-export-page-test.csv')
      ..writeAsStringSync('a;b');
    return f;
  }

  // Story 15.2 — « Mes exports » belongs to the paid exports module.
  testWidgets(
      'exports DISABLED: no « Mes exports » icon; the free export '
      'screen stays', (tester) async {
    await pumpApp(
        tester,
        app(extra: [
          entitlementsOverride({'exports': 'DISABLED'})
        ]));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('export-my-exports')), findsNothing);
    expect(find.text('Produits'), findsOneWidget);
  });

  testWidgets('exports ENABLED: the « Mes exports » icon opens the list',
      (tester) async {
    await pumpApp(
        tester,
        app(extra: [
          entitlementsOverride({'exports': 'ENABLED'})
        ]));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-my-exports');
    expect(find.text('MES EXPORTS'), findsOneWidget);
  });

  testWidgets('datasets from the server, « Tout », direct export is shared',
      (tester) async {
    final file = tempFile();
    ds.onRequest = (_) async => ExportFileReady(file);

    await pumpApp(tester, app());
    await tester.pumpAndSettle();

    expect(find.text('Produits'), findsOneWidget);
    expect(find.text('Ventes'), findsOneWidget);
    expect(find.text('Journal d\'audit'), findsOneWidget);
    expect(submit(tester).onPressed, isNull, reason: 'nothing selected');

    await tapKey(tester, 'export-all');
    await tapKey(tester, 'export-submit');

    expect(ds.requests.single.datasets, ['products', 'sales', 'audit_log']);
    expect(ds.requests.single.format, ExportFormat.csv);
    expect(shared.single.path, file.path);
    expect(find.text('Fichier prêt'), findsOneWidget);

    await tapKey(tester, 'export-all');
    expect(submit(tester).onPressed, isNull, reason: '« Tout » toggles off');
  });

  testWidgets('Free plan: Excel opens the offer sheet, format stays CSV',
      (tester) async {
    premium = false;
    ds.onRequest = (_) async => ExportFileReady(tempFile());

    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    await tester.pumpAndSettle();

    expect(find.text('Premium'), findsNWidgets(2), reason: 'Excel and PDF');
    await tapKey(tester, 'export-format-xlsx');
    expect(find.byKey(const Key('premium_feature_cta')), findsOneWidget);

    await tester.tap(find.text('Plus tard'));
    await tester.pumpAndSettle();
    await tapKey(tester, 'export-submit');
    expect(ds.requests.single.format, ExportFormat.csv);

    await tapKey(tester, 'export-format-xlsx');
    await tester.tap(find.byKey(const Key('premium_feature_cta')));
    await tester.pumpAndSettle();
    expect(find.text('ABONNEMENT'), findsOneWidget);
  });

  testWidgets('Premium: Excel and PDF selectable without badge',
      (tester) async {
    ds.onRequest = (_) async => ExportFileReady(tempFile());
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();

    expect(find.text('Premium'), findsNothing);
    expect(find.text('Bientôt'), findsNothing);
    final pdf =
        tester.widget<ListTile>(find.byKey(const Key('export-format-pdf')));
    expect(pdf.enabled, isNot(isFalse));

    await tapKey(tester, 'export-format-xlsx');
    await tapKey(tester, 'export-submit');
    expect(ds.requests.single.format, ExportFormat.xlsx);
  });

  // ── PDF templates (v1s-18-3) ───────────────────────────────────────────────

  testWidgets('Free plan: PDF opens the PDF offer sheet, no request sent',
      (tester) async {
    premium = false;
    ds.datasets = pdfTestDatasets;
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-format-pdf');
    expect(find.byKey(const Key('premium_feature_cta')), findsOneWidget);
    expect(find.text('Le PDF est réservé au plan Premium'), findsOneWidget);
    await tester.tap(find.text('Plus tard'));
    await tester.pumpAndSettle();

    expect(ds.requests, isEmpty);
    expect(find.byKey(const Key('export-template-sales')), findsNothing,
        reason: 'format stays CSV: the dataset checkboxes remain');
    expect(find.byKey(const Key('export-dataset-sales')), findsOneWidget);
  });

  testWidgets(
      'Premium PDF: « Modèle » list replaces the datasets, submit waits for a '
      'template, « État de stock » sends datasets=[stock_levels] format PDF',
      (tester) async {
    ds.datasets = pdfTestDatasets;
    final file = tempFile();
    ds.onRequest = (_) async => ExportFileReady(file);
    await pumpApp(
        tester,
        app(
            prefill: const ExportPrefill(
                datasets: {'products', 'audit_log'}, storeId: 's-1')));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-format-pdf');

    expect(find.text('Modèle'), findsOneWidget);
    expect(find.byKey(const Key('export-dataset-products')), findsNothing);
    expect(find.byKey(const Key('export-all')), findsNothing);
    expect(find.text('Liste des ventes'), findsOneWidget);
    expect(find.text('État de stock'), findsOneWidget);
    expect(find.byKey(const Key('export-template-products')), findsNothing);
    expect(submit(tester).onPressed, isNull,
        reason: 'no templated dataset was selected: nothing preselected');

    await tapKey(tester, 'export-template-stock_levels');
    expect(
        tester.widget<ListTile>(find.byKey(const Key('export-period'))).enabled,
        isFalse,
        reason: 'the stock is a snapshot: no period');
    expect(
        tester.widget<ListTile>(find.byKey(const Key('export-store'))).enabled,
        isTrue);
    await tapKey(tester, 'export-submit');

    expect(ds.requests.single.toJson(), {
      'datasets': ['stock_levels'],
      'format': 'PDF',
      'storeId': 's-1',
    });
    expect(shared.single.path, file.path);
  });

  testWidgets(
      'PDF preselects the one templated dataset; back to CSV restores the '
      'dataset selection', (tester) async {
    ds.datasets = pdfTestDatasets;
    ds.onRequest = (_) async => ExportFileReady(tempFile());
    await pumpApp(tester,
        app(prefill: const ExportPrefill(datasets: {'sales', 'products'})));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-format-pdf');
    final node = tester.getSemantics(find
        .ancestor(
            of: find.byKey(const Key('export-template-sales')),
            matching: find.byType(Semantics))
        .first);
    expect(node.hasFlag(SemanticsFlag.isSelected), isTrue);
    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.datasets, ['sales']);
    expect(ds.requests.last.format, ExportFormat.pdf);

    await tapKey(tester, 'export-format-csv');
    expect(
        tester
            .widget<CheckboxListTile>(
                find.byKey(const Key('export-dataset-products')))
            .value,
        isTrue);
    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.datasets, ['products', 'sales']);
    expect(ds.requests.last.format, ExportFormat.csv);
  });

  testWidgets('PDF too large: the server message is shown with « Réessayer »',
      (tester) async {
    ds.datasets = pdfTestDatasets;
    ds.onRequest = (_) async => throw ExportException.fromResponse(422, {
          'domainCode': 'EXPORT_PDF_TOO_LARGE',
          'error': 'Ce PDF dépasserait la limite de 2000 lignes : réduisez '
              'la période, choisissez une boutique ou exportez en Excel',
        });
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();
    await tapKey(tester, 'export-format-pdf');
    await tapKey(tester, 'export-submit');
    expect(find.textContaining('limite de 2000 lignes'), findsOneWidget);
    expect(find.byKey(const Key('export-retry')), findsOneWidget);
  });

  testWidgets('server plan gate (403) opens the offer sheet', (tester) async {
    ds.onRequest = (_) async => throw const ExportException(
        domainCode: 'PLAN_FEATURE_REQUIRED', message: 'x', statusCode: 403);
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();
    await tapKey(tester, 'export-submit');
    expect(find.byKey(const Key('premium_feature_cta')), findsOneWidget);
    expect(find.byKey(const Key('export-error')), findsNothing);
  });

  testWidgets('audit prefill: criteria sent alone, note + dropped with others',
      (tester) async {
    ds.onRequest = (_) async => ExportFileReady(tempFile());
    final from = DateTime(2026, 10, 1);
    final to = DateTime(2026, 10, 12, 23, 59, 59, 999);
    await pumpApp(
        tester,
        app(
          prefill: ExportPrefill(
            datasets: const {'audit_log'},
            storeId: 's-1',
            from: from,
            to: to,
            criteria: const {
              'userId': 'u-1',
              'action': 'PRODUCT_CREATED',
              'entityType': 'Product',
              'q': 'pagne',
            },
          ),
        ));
    await tester.pumpAndSettle();

    final audit = tester.widget<CheckboxListTile>(
        find.byKey(const Key('export-dataset-audit_log')));
    expect(audit.value, isTrue);
    expect(
        tester
            .widget<CheckboxListTile>(
                find.byKey(const Key('export-dataset-sales')))
            .value,
        isFalse);
    expect(find.text('Boutique Centre'), findsOneWidget);
    expect(find.text('01/10/2026 – 12/10/2026'), findsOneWidget);
    expect(find.byKey(const Key('export-criteria-note')), findsNothing);

    await tapKey(tester, 'export-submit');
    final sole = ds.requests.last.toJson();
    expect(sole['criteria'], {
      'userId': 'u-1',
      'action': 'PRODUCT_CREATED',
      'entityType': 'Product',
      'q': 'pagne',
    });
    expect(sole['storeId'], 's-1');
    expect(sole['from'], from.toUtc().toIso8601String());

    await tapKey(tester, 'export-dataset-sales');
    expect(find.byKey(const Key('export-criteria-note')), findsOneWidget);
    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.toJson().containsKey('criteria'), isFalse);

    // Criteria are kept in memory: back to the audit log alone.
    await tapKey(tester, 'export-dataset-sales');
    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.criteria?['q'], 'pagne');
  });

  testWidgets('an error shows the message; « Réessayer » replays the request',
      (tester) async {
    var calls = 0;
    final file = tempFile();
    ds.onRequest = (_) async {
      calls++;
      if (calls == 1) {
        throw ExportException.fromResponse(429, {
          'domainCode': 'EXPORT_QUEUE_FULL',
        });
      }
      return ExportFileReady(file);
    };
    await pumpApp(tester,
        app(prefill: const ExportPrefill(datasets: {'sales'}, storeId: 's-1')));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-submit');
    expect(
        find.text('Trop d\'exports en cours, réessayez dans quelques minutes'),
        findsOneWidget);

    await tapKey(tester, 'export-retry');
    expect(ds.requests, hasLength(2));
    expect(ds.requests[1].toJson(), ds.requests[0].toJson());
    expect(find.byKey(const Key('export-error')), findsNothing);
    expect(shared.single.path, file.path);
  });

  testWidgets('network error is explained', (tester) async {
    ds.onRequest = (_) async => throw ExportException.network;
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    await tester.pumpAndSettle();
    await tapKey(tester, 'export-submit');
    expect(find.text('Impossible de joindre le serveur'), findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget);
  });

  testWidgets(
      'offline: banner, launch disabled, local CSV entry opens '
      '/exports/local with the current choices (v1s-18-5)', (tester) async {
    online = false;
    final from = DateTime(2026, 10, 1);
    final to = DateTime(2026, 10, 7, 23, 59);
    await pumpApp(
        tester,
        app(
            prefill: ExportPrefill(
                datasets: const {'products', 'sales'},
                storeId: 's-1',
                from: from,
                to: to,
                criteria: const {'action': 'SALE_CREATED'})));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('export-offline-banner')), findsOneWidget);
    expect(find.text('Hors ligne — l\'export complet nécessite une connexion'),
        findsOneWidget);
    expect(submit(tester).onPressed, isNull);
    final local = tester
        .widget<ListTile>(find.byKey(const Key('export-local-csv-entry')));
    expect(local.enabled, isTrue);
    expect(find.text('CSV — données de cet appareil'), findsOneWidget);
    expect(
        find.text(
            'Contient uniquement les données synchronisées sur cet appareil'),
        findsOneWidget);
    expect(find.text('Bientôt disponible'), findsNothing);

    await tapKey(tester, 'export-local-csv-entry');
    expect(find.text('LOCAL CSV'), findsOneWidget);
    expect(localPrefill!.datasets, {'products', 'sales'});
    expect(localPrefill!.storeId, 's-1');
    expect(localPrefill!.from, from);
    expect(localPrefill!.to, to);
    expect(localPrefill!.criteria, {'action': 'SALE_CREATED'});
  });

  testWidgets('online: no local CSV entry until the server is unreachable',
      (tester) async {
    ds.onRequest = (_) async => throw ExportException.network;
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('export-local-csv-entry')), findsNothing);

    await tapKey(tester, 'export-submit');
    expect(find.byKey(const Key('export-error')), findsOneWidget);
    expect(find.byKey(const Key('export-local-csv-entry')), findsOneWidget);
    await tapKey(tester, 'export-local-csv-entry');
    expect(find.text('LOCAL CSV'), findsOneWidget);
    expect(localPrefill!.datasets, {'products'});
  });

  testWidgets('other errors do not offer the local CSV', (tester) async {
    ds.onRequest = (_) async => throw ExportException.unexpected;
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    await tester.pumpAndSettle();
    await tapKey(tester, 'export-submit');
    expect(find.byKey(const Key('export-error')), findsOneWidget);
    expect(find.byKey(const Key('export-local-csv-entry')), findsNothing);
  });

  testWidgets('background export: confirmation and link to Mes exports',
      (tester) async {
    ds.onRequest = (_) async => const ExportQueued(ExportJob(
        id: 'job-1', status: ExportJobStatus.pending, format: 'XLSX'));
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-submit');
    expect(
        find.text('Votre export se prépare, nous vous prévenons dès qu\'il '
            'est prêt'),
        findsOneWidget);
    expect(shared, isEmpty);

    await tapKey(tester, 'export-see-my-exports');
    expect(find.text('MES EXPORTS'), findsOneWidget);
  });

  testWidgets('store / period pickers disabled for reference datasets only',
      (tester) async {
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'products'})));
    await tester.pumpAndSettle();
    expect(
        tester.widget<ListTile>(find.byKey(const Key('export-store'))).enabled,
        isFalse);
    expect(
        tester.widget<ListTile>(find.byKey(const Key('export-period'))).enabled,
        isFalse);

    await tapKey(tester, 'export-dataset-sales');
    await tapKey(tester, 'export-period');
    await tester.tap(find.text('7 jours'));
    await tester.pumpAndSettle();
    expect(find.text('7 jours'), findsOneWidget, reason: 'tile subtitle');
  });

  testWidgets('changing a choice clears the error and its replay',
      (tester) async {
    ds.onRequest = (_) async => throw ExportException.network;
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();
    await tapKey(tester, 'export-submit');
    expect(find.byKey(const Key('export-error')), findsOneWidget);

    await tapKey(tester, 'export-dataset-products');
    expect(find.byKey(const Key('export-error')), findsNothing);

    await tapKey(tester, 'export-submit');
    expect(find.byKey(const Key('export-error')), findsOneWidget);
    await tapKey(tester, 'export-format-xlsx');
    expect(find.byKey(const Key('export-error')), findsNothing);
  });

  testWidgets('unknown plan + server plan gate falls back to CSV',
      (tester) async {
    premium = null;
    ds.onRequest = (r) async => r.format == ExportFormat.xlsx
        ? throw const ExportException(
            domainCode: 'PLAN_FEATURE_REQUIRED', message: 'x', statusCode: 403)
        : ExportFileReady(tempFile());
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-format-xlsx');
    await tapKey(tester, 'export-submit');
    expect(find.byKey(const Key('premium_feature_cta')), findsOneWidget);
    await tester.tap(find.text('Plus tard'));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.format, ExportFormat.csv);
  });

  testWidgets(
      'unknown plan + server plan gate on PDF shows the PDF sheet, then the '
      'datasets come back and CSV is sent with the original selection',
      (tester) async {
    premium = null;
    ds.datasets = pdfTestDatasets;
    ds.onRequest = (r) async => r.format == ExportFormat.pdf
        ? throw const ExportException(
            domainCode: 'PLAN_FEATURE_REQUIRED', message: 'x', statusCode: 403)
        : ExportFileReady(tempFile());
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-format-pdf');
    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.format, ExportFormat.pdf);
    expect(find.text('Le PDF est réservé au plan Premium'), findsOneWidget);
    await tester.tap(find.text('Plus tard'));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('export-dataset-sales')), findsOneWidget);
    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.format, ExportFormat.csv);
    expect(ds.requests.last.datasets, ['sales']);
  });

  // 13.10b — since 13.10a the server refuses with MODULE_NOT_ENTITLED.
  testWidgets('XLSX refused with MODULE_NOT_ENTITLED opens the upgrade sheet',
      (tester) async {
    premium = null;
    ds.onRequest = (r) async => r.format == ExportFormat.xlsx
        ? throw const ExportException(
            domainCode: 'MODULE_NOT_ENTITLED', message: 'x', statusCode: 403)
        : ExportFileReady(tempFile());
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-format-xlsx');
    await tapKey(tester, 'export-submit');
    expect(find.text('Excel est réservé au plan Premium'), findsOneWidget);
    expect(find.byKey(const Key('premium_feature_cta')), findsOneWidget);
    expect(find.byKey(const Key('export-error')), findsNothing);
    await tester.tap(find.text('Plus tard'));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-submit');
    expect(ds.requests.last.format, ExportFormat.csv);
  });

  testWidgets('CSV of premium data refused → premium data sheet',
      (tester) async {
    ds.onRequest = (r) async => throw const ExportException(
        domainCode: 'MODULE_NOT_ENTITLED', message: 'x', statusCode: 403);
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();

    await tapKey(tester, 'export-submit');
    expect(find.text('Données réservées au plan Premium'), findsOneWidget);
    expect(find.byKey(const Key('premium_feature_cta')), findsOneWidget);
  });

  testWidgets('prefilled keys missing from the catalogue are dropped',
      (tester) async {
    ds.onRequest = (_) async => ExportFileReady(tempFile());
    await pumpApp(
        tester,
        app(
            prefill:
                const ExportPrefill(datasets: {'sales', 'variants_gone'})));
    await tester.pumpAndSettle();
    await tapKey(tester, 'export-submit');
    expect(ds.requests.single.datasets, ['sales']);
  });

  testWidgets('offline banner « Réessayer » re-checks the connection',
      (tester) async {
    online = false;
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();
    expect(submit(tester).onPressed, isNull);

    online = true;
    await tapKey(tester, 'export-offline-retry');
    expect(find.byKey(const Key('export-offline-banner')), findsNothing);
    expect(submit(tester).onPressed, isNotNull);
  });

  Finder formatNode(String f) => find
      .ancestor(
          of: find.byKey(Key('export-format-$f')),
          matching: find.byType(Semantics))
      .first;

  testWidgets('format tiles expose their selected state', (tester) async {
    await pumpApp(
        tester, app(prefill: const ExportPrefill(datasets: {'sales'})));
    await tester.pumpAndSettle();
    final csv = tester.getSemantics(formatNode('csv'));
    expect(csv.hasFlag(SemanticsFlag.isSelected), isTrue);
    expect(csv.hasFlag(SemanticsFlag.isInMutuallyExclusiveGroup), isTrue);
    final xlsx = tester.getSemantics(formatNode('xlsx'));
    expect(xlsx.hasFlag(SemanticsFlag.isSelected), isFalse);
  });
}
