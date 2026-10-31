import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/catalog/domain/model/csv_import_result.dart';
import 'package:keevo/features/catalog/presentation/page/csv_import_page.dart';
import 'package:keevo/features/catalog/presentation/provider/csv_import_provider.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// Picks a fixed file and fails the import, without touching the platform.
class _FailingImportNotifier extends CsvImportNotifier {
  int importCalls = 0;

  @override
  CsvImportState build() => const CsvImportIdle();

  @override
  Future<void> pickFile() async {
    state = CsvImportFilePicked(
      bytes: Uint8List.fromList(utf8.encode('nom,prix\nRiz,500\n')),
      fileName: 'f.csv',
      detectedHeaders: const ['nom', 'prix'],
    );
  }

  @override
  Future<void> runImport({required Map<String, String> mapping}) async {
    importCalls++;
    final picked = state as CsvImportFilePicked;
    state = CsvImportLoading(picked: picked);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    state = CsvImportError('Connexion requise', picked: picked);
  }
}

/// Picks a fixed file and succeeds the import (3 imported, 1 skipped).
class _SucceedingImportNotifier extends CsvImportNotifier {
  @override
  CsvImportState build() => const CsvImportIdle();

  @override
  Future<void> pickFile() async {
    state = CsvImportFilePicked(
      bytes: Uint8List.fromList(utf8.encode('nom,prix_vente\nRiz,500\n')),
      fileName: 'f.csv',
      detectedHeaders: const ['nom', 'prix_vente'],
    );
  }

  @override
  Future<void> runImport({required Map<String, String> mapping}) async {
    final picked = state as CsvImportFilePicked;
    state = CsvImportLoading(picked: picked);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    state = const CsvImportSuccess(CsvImportResult(
      imported: 3,
      skipped: 1,
      errors: [CsvRowError(line: 5, column: 'prix_vente', message: 'prix_vente doit être un entier')],
    ));
  }
}

/// Each pick returns the next file of [files] (headers only matter here).
class _SequenceNotifier extends CsvImportNotifier {
  _SequenceNotifier(this.files);
  final List<List<String>> files;
  int _next = 0;

  @override
  CsvImportState build() => const CsvImportIdle();

  @override
  Future<void> pickFile() async {
    final headers = files[_next++];
    state = CsvImportFilePicked(
      bytes: Uint8List.fromList(utf8.encode('${headers.join(',')}\n')),
      fileName: 'f$_next.csv',
      detectedHeaders: headers,
    );
  }
}

Future<void> _map(WidgetTester tester, String field, String header) async {
  await tester.tap(find.text(field));
  await tester.pumpAndSettle();
  await tester.tap(find.text(header).last);
  await tester.pumpAndSettle();
}

String? _value(WidgetTester tester, String field) => tester
    .widget<DropdownButton<String>>(find.descendant(
        of: find.ancestor(
            of: find.text(field),
            matching: find.byWidgetPredicate(
                (w) => w is DropdownButtonFormField<String>)),
        matching: find.byWidgetPredicate((w) => w is DropdownButton<String>)))
    .value;

void main() {
  testWidgets('mapping step stays visible during and after a failed import',
      (tester) async {
    final notifier = _FailingImportNotifier();
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [csvImportNotifierProvider.overrideWith(() => notifier)],
      child: const MaterialApp(home: CsvImportPage()),
    ));

    await tester.tap(find.text('Choisir un fichier .csv'));
    await tester.pumpAndSettle();
    expect(find.text('Correspondance des colonnes'), findsOneWidget);

    await tester.tap(find.text('Nom du produit *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('nom').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Prix de vente *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('prix').last);
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Lancer l\'import'));
    await tester.tap(find.text('Lancer l\'import'));
    await tester.pump();
    expect(find.text('Correspondance des colonnes'), findsOneWidget);
    expect(find.text('Import en cours…'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 20));
    await tester.pumpAndSettle();
    expect(find.text('Correspondance des colonnes'), findsOneWidget);
    expect(find.text('Connexion requise'), findsOneWidget);
    // The user can retry from the same mapping.
    expect(
      tester
          .widget<FilledButton>(find.ancestor(
              of: find.text('Lancer l\'import'),
              matching: find.byWidgetPredicate((w) => w is FilledButton)))
          .onPressed,
      isNotNull,
    );
  });

  // v1s-16-13: a stale mapping is not in the dropdown items.
  testWidgets('cancel clears the mapping; a new file keeps only its columns',
      (tester) async {
    final notifier = _SequenceNotifier([
      ['nom', 'prix', 'sku'],
      ['nom', 'tarif', 'sku'],
      ['nom', 'sku'],
    ]);
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [csvImportNotifierProvider.overrideWith(() => notifier)],
      child: const MaterialApp(home: CsvImportPage()),
    ));

    await tester.tap(find.text('Choisir un fichier .csv'));
    await tester.pumpAndSettle();
    await _map(tester, 'Nom du produit *', 'nom');
    await _map(tester, 'Prix de vente *', 'prix');
    expect(_value(tester, 'Prix de vente *'), 'prix');

    // Cancel, then pick another file: nothing carried over — only what the
    // new headers suggest ("nom" is recognised, "tarif" is not a known alias).
    await tester.ensureVisible(find.text('Annuler'));
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Choisir un fichier .csv'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(_value(tester, 'Nom du produit *'), 'nom');
    expect(_value(tester, 'Prix de vente *'), isNull);

    // Map name + price, then a new file that has "nom" but not "tarif".
    await _map(tester, 'Nom du produit *', 'nom');
    await _map(tester, 'Prix de vente *', 'tarif');
    await notifier.pickFile();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(_value(tester, 'Nom du produit *'), 'nom');
    expect(_value(tester, 'Prix de vente *'), isNull);
  });

  // Device test: the import succeeded on the server but the page stayed on
  // the mapping step — the user could not tell and would import again.
  testWidgets('a successful import shows the result step', (tester) async {
    await tester.pumpWidget(ProviderScope(
      retry: noProviderRetry,
      overrides: [
        csvImportNotifierProvider.overrideWith(_SucceedingImportNotifier.new),
      ],
      child: const MaterialApp(home: CsvImportPage()),
    ));
    await tester.tap(find.text('Choisir un fichier .csv'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Lancer l\'import'));
    await tester.tap(find.text('Lancer l\'import'));
    await tester.pump(const Duration(milliseconds: 20));
    await tester.pumpAndSettle();

    expect(find.text('Correspondance des colonnes'), findsNothing);
    expect(find.textContaining('Lignes ignorées'), findsOneWidget);
    // J12 — French field label, never the raw key.
    expect(find.text('Ligne 5 — Prix de vente : Prix de vente doit être un entier'),
        findsOneWidget);
    expect(find.textContaining('prix_vente'), findsNothing);
  });

  group('J12 — csvImportErrorLine', () {
    test('server labels are kept as is', () {
      expect(
          csvImportErrorLine(
              3, 'Prix de vente', 'Prix de vente est requis et doit être un entier positif'),
          'Ligne 3 — Prix de vente : Prix de vente est requis et doit être un entier positif');
    });

    test('legacy raw keys are translated, plain words untouched', () {
      expect(
          csvImportErrorLine(6, 'quantite_initiale',
              "quantite_initiale doit être un entier, reçu : 'abc'"),
          "Ligne 6 — Quantité initiale : Quantité initiale doit être un entier, reçu : 'abc'");
      expect(
          csvImportErrorLine(5, 'nom', 'Un produit avec ce nom existe déjà: Riz'),
          'Ligne 5 — Nom : Un produit avec ce nom existe déjà: Riz');
    });

    test('placeholder column « - » is dropped', () {
      expect(csvImportErrorLine(4, '-', 'Produit non importé'),
          'Ligne 4 — Produit non importé');
    });
  });
}
