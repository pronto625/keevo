import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/inventory/presentation/page/local_low_stock_page.dart';
import 'package:keevo_core/entitlements/entitlements.dart';

/// Story 15.8 — the predicate shared by the low-stock tap and the catalogue
/// button's tooltip.
void main() {
  Entitlements known(Map<String, String> states) =>
      Entitlements(states: states, status: EntitlementsStatus.server);

  test('unknown states → local list', () {
    expect(canOpenStockOverview(Entitlements.unknown), isFalse);
  });

  test('multi-store ENABLED / READ_ONLY → overview; DISABLED → local', () {
    expect(canOpenStockOverview(known({'multi-store': 'ENABLED'})), isTrue);
    expect(canOpenStockOverview(known({'multi-store': 'READ_ONLY'})), isTrue);
    expect(canOpenStockOverview(known({'multi-store': 'DISABLED'})), isFalse);
    expect(canOpenStockOverview(known({})), isFalse);
  });

  test('query string allowed', () {
    expect(
        canOpenStockOverview(known({'multi-store': 'ENABLED'}),
            overviewRoute: '/stock/overview?showLowOnly=true'),
        isTrue);
  });

  test('a route outside the registry never opens (no fail-open)', () {
    expect(
        canOpenStockOverview(known({'multi-store': 'ENABLED'}),
            overviewRoute: '/stock/low'),
        isFalse);
  });
}
