import 'dart:convert';
import 'dart:io';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/export/data/local/local_csv_writer.dart';
import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/local/local_export_schema.dart';

/// v1s-18-5 — the worker-isolate writer: BOM, header of French labels,
/// CRLF, server cell formats; a zip of `<label slug>.csv` for several
/// datasets (v1s-16-20: never the dataset key).
void main() {
  late Directory dir;

  setUp(() => dir = Directory.systemTemp.createTempSync('local_csv_writer'));
  tearDown(() {
    if (dir.existsSync()) dir.deleteSync(recursive: true);
  });

  final products = localExportDataset('products')!;
  final sales = localExportDataset('sales')!;

  List<Object?> productRow(String sku) => [
        sku,
        '=SUM(A1)',
        'Tissus',
        'Actif',
        1500,
        1000,
        0,
        null,
        3,
        'A; B',
        true,
        DateTime.utc(2026, 10, 12, 23, 30),
        DateTime.utc(2026, 10, 12, 23, 30),
      ];

  test('one dataset: BOM, header, CRLF, formatted cells', () async {
    final writer = LocalCsvWriter();
    await writer.start(dir);
    await writer.beginDataset('products', products.columns);
    await writer.addRows([productRow('p-1'), productRow('p-2')]);
    await writer.addRows([productRow('p-3')]);
    await writer.endDataset();
    final file = await writer.finish('out.csv');

    expect(file.path, '${dir.path}${Platform.pathSeparator}out.csv');
    final bytes = file.readAsBytesSync();
    expect(bytes.sublist(0, 3), [0xEF, 0xBB, 0xBF]);
    final text = utf8.decode(bytes.sublist(3));
    final lines = text.split('\r\n');
    expect(
        lines.first,
        'Référence;Nom;Catégorie;Statut;Prix de vente;'
        'Prix d\'achat;Frais de transport;Seuil minimum;Stock;Fournisseurs;'
        'Archivé;Créé le;Modifié le');
    expect(
        lines[1],
        "p-1;'=SUM(A1);Tissus;Actif;1500;1000;0;;3;\"A; B\";Oui;"
        '13/10/2026 00:30:00;13/10/2026 00:30:00');
    expect(lines, hasLength(5)); // header + 3 rows + trailing empty
    expect(lines.last, '');
    expect(text.replaceAll('\r\n', ''), isNot(contains('\n')));
    // The parts folder is gone.
    expect(Directory('${dir.path}/.parts').existsSync(), isFalse);
  });

  test('several datasets: zip of <label slug>.csv entries, each with a BOM',
      () async {
    final writer = LocalCsvWriter();
    await writer.start(dir);
    await writer.beginDataset('products', products.columns);
    await writer.addRows([productRow('p-1')]);
    await writer.endDataset();
    await writer.beginDataset('sales', sales.columns);
    await writer.addRows([
      [
        's-1',
        DateTime.utc(2026, 10, 1, 9),
        'A',
        'Awa',
        null,
        'Annulée',
        'Mobile Money',
        'MP2603.1234',
        2,
        0,
        3000
      ],
    ]);
    await writer.endDataset();
    final file = await writer.finish('out.zip');

    final archive = ZipDecoder().decodeBytes(file.readAsBytesSync());
    expect(archive.files.map((f) => f.name), ['produits.csv', 'ventes.csv']);
    for (final f in archive.files) {
      final content = f.content;
      expect(content.sublist(0, 3), [0xEF, 0xBB, 0xBF], reason: f.name);
    }
    final salesCsv = utf8.decode(archive.files[1].content.sublist(3));
    expect(salesCsv.split('\r\n')[1],
        's-1;01/10/2026 10:00:00;A;Awa;;Annulée;Mobile Money;MP2603.1234;2;0;3000');
  });

  test('storage failure surfaces as ExportException.storage', () async {
    final blocker = File('${dir.path}/not-a-dir')..writeAsStringSync('x');
    final writer = LocalCsvWriter();
    await expectLater(writer.start(Directory(blocker.path)),
        throwsA(same(ExportException.storage)));
    await writer.abort();
  });
}
