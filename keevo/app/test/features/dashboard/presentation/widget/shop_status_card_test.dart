import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/dashboard_snapshot.dart';
import 'package:keevo/features/dashboard/presentation/widget/shop_status_card.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('ShopStatusCard', () {
    testWidgets('displays store name and today CA', (tester) async {
      await tester.pumpWidget(_wrap(ShopStatusCard(
        store: const StoreOverview(
          storeId: 's1',
          storeName: 'Boutique Centrale',
          todayCA: 25000,
          yesterdayCA: 20000,
          employeeCount: 4,
          statusLevel: StoreStatusLevel.stable,
        ),
      )));

      expect(find.text('Boutique Centrale'), findsOneWidget);
      expect(find.textContaining('25'), findsAtLeast(1));
    });

    testWidgets('shows employee count', (tester) async {
      await tester.pumpWidget(_wrap(ShopStatusCard(
        store: const StoreOverview(
          storeId: 's2',
          storeName: 'Boutique Nord',
          todayCA: 10000,
          yesterdayCA: 30000,
          employeeCount: 7,
          statusLevel: StoreStatusLevel.enBaisse,
        ),
      )));

      expect(find.text('7'), findsOneWidget);
    });

    testWidgets('onTap callback fires', (tester) async {
      var tapped = false;
      await tester.pumpWidget(_wrap(ShopStatusCard(
        store: const StoreOverview(
          storeId: 's1',
          storeName: 'Store',
          todayCA: 0,
          yesterdayCA: 0,
          employeeCount: 1,
          statusLevel: StoreStatusLevel.stable,
        ),
        onTap: () => tapped = true,
      )));

      await tester.tap(find.byType(ShopStatusCard));
      expect(tapped, isTrue);
    });
  });
}
