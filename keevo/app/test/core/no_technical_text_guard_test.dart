import 'package:flutter_test/flutter_test.dart';

import '../helpers/no_technical_text.dart';

/// v1s-16-20 — the guard itself flags every technical form NFR35 forbids.
void main() {
  test('flags ids, codes (incl. single-word enums), JSON and truncated ids',
      () {
    for (final text in [
      '7f1c2b4e-2b9a-4c1e-9d0f-0a1b2c3d4e5f',
      'Statut : PENDING_VALIDATION',
      'Source : SYNC',
      'Paiement : CASH',
      'Type : WAREHOUSE',
      '{"a":1}',
      'Appareil …abcd1234',
      'abcd1234…',
      '7f1c2b4e-2b9a…',
      'ID ...ABCD1234',
    ]) {
      expect(technicalFindings(text), isNotEmpty, reason: text);
    }
  });

  test('French labels pass', () {
    for (final text in [
      'Synchronisé depuis l\'appareil de Awa',
      'Prix : 1 000 XAF → 1 500 XAF',
      'Export CSV — Ventes',
      'Stock : OK',
    ]) {
      expect(technicalFindings(text), isEmpty, reason: text);
    }
  });
}
