import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/utils/fr_plural.dart';

/// A32 — « 1 unités » on the profitability rows.
void main() {
  test('0 and 1 are singular in French, 2 and more plural', () {
    expect(unitsLabel(0), '0 unité');
    expect(unitsLabel(1), '1 unité');
    expect(unitsLabel(2), '2 unités');
    expect(unitsLabel(12), '12 unités');
  });

  test('frCount agrees any noun', () {
    expect(frCount(1, 'vente', 'ventes'), '1 vente');
    expect(frCount(3, 'vente', 'ventes'), '3 ventes');
  });
}
