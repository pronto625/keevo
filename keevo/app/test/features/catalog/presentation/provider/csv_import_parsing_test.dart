import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/catalog/presentation/provider/csv_import_provider.dart';

void main() {
  group('CSV header/preview parsing', () {
    test('headers are decoded as UTF-8 like the backend', () {
      final bytes = utf8.encode('nom,prix_vente,Catégorie\nRiz,500,Épicerie\n');
      expect(csvHeadersFromBytes(bytes), ['nom', 'prix_vente', 'Catégorie']);
      expect(csvPreviewRows(bytes), [
        ['Riz', '500', 'Épicerie'],
      ]);
    });

    test('UTF-8 BOM is stripped from the first header', () {
      final bytes = [0xEF, 0xBB, 0xBF, ...utf8.encode('nom,prix\n')];
      expect(csvHeadersFromBytes(bytes), ['nom', 'prix']);
    });

    test('quoted fields keep their commas', () {
      final bytes =
          utf8.encode('nom,"prix, FCFA"\n"Riz, 5kg",2500\n"Huile ""Dinor""",1200\n');
      expect(csvHeadersFromBytes(bytes), ['nom', 'prix, FCFA']);
      expect(csvPreviewRows(bytes), [
        ['Riz, 5kg', '2500'],
        ['Huile "Dinor"', '1200'],
      ]);
    });

    test('a blank header column does not shift the preview cells', () {
      final bytes = utf8.encode('a,,b\n1,2,3\n');
      expect(csvHeadersFromBytes(bytes), ['a', 'b']);
      expect(csvPreviewRows(bytes), [
        ['1', '3'],
      ]);
    });

    test('a repeated header keeps its first column only', () {
      final bytes = utf8.encode('nom,prix,nom\nRiz,2500,X\n');
      expect(csvHeadersFromBytes(bytes), ['nom', 'prix']);
      expect(csvPreviewRows(bytes), [
        ['Riz', '2500'],
      ]);
    });
  });
}
