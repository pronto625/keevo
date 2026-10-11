import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/catalog/presentation/page/csv_import_page.dart';
import 'package:keevo/features/catalog/presentation/provider/csv_import_provider.dart';

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

void main() {
  testWidgets('mapping step stays visible during and after a failed import',
      (tester) async {
    final notifier = _FailingImportNotifier();
    await tester.pumpWidget(ProviderScope(
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
}
