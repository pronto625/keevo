import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/features/catalog/domain/model/cross_store_availability_model.dart';
import 'package:keevo/features/catalog/domain/repository/stock_repository.dart';
import 'package:keevo/features/catalog/presentation/provider/cross_store_availability_provider.dart';
import 'package:keevo/features/catalog/presentation/provider/stock_provider.dart';
import 'package:keevo/features/catalog/presentation/widget/cross_store_availability_bottom_sheet.dart';
import 'package:keevo/features/stores/domain/model/store_model.dart';
import 'package:keevo/features/stores/presentation/provider/store_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _MockStockRepository extends Mock implements StockRepository {}

// Stub notifier that immediately returns an empty list — prevents real infra
// from being initialised.
class _StubStoreListNotifier extends StoreListNotifier {
  @override
  Future<List<StoreModel>> build() async => [];
}

/// Widget tests for CrossStoreAvailabilityBottomSheet (Story 3.4 — Task 10).
void main() {
  setUpAll(() {
    // Prevent google_fonts network calls in tests
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  late _MockStockRepository mockRepo;
  late SharedPreferences prefs;

  final tModel = CrossStoreAvailabilityModel(
    productId: 'p1',
    productName: 'Produit A',
    entries: [
      CrossStoreAvailabilityEntry(
        storeId: 's1',
        storeName: 'Boutique Centre',
        quantity: 15,
        minimumThreshold: 3,
        isLow: false,
      ),
      CrossStoreAvailabilityEntry(
        storeId: 's2',
        storeName: 'Entrepôt Nord',
        storeType: 'WAREHOUSE',
        quantity: 0,
        minimumThreshold: 0,
        isLow: false,
      ),
    ],
    refreshedAt: DateTime(2024, 1, 1),
  );

  setUp(() async {
    mockRepo = _MockStockRepository();
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  void _setPhoneSize(WidgetTester tester) {
    tester.view.physicalSize = const Size(400, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  Widget _buildSheet({
    String role = 'OWNER',
    CrossStoreAvailabilityModel? modelOverride,
  }) {
    prefs.setString('user_role', role);
    final modelToUse = modelOverride ?? tModel;

    return ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        stockRepositoryProvider.overrideWithValue(mockRepo),
        crossStoreAvailabilityProvider('p1')
            .overrideWith((_) => Future.value(modelToUse)),
        // Prevent real store infra from being initialised
        storeListNotifierProvider
            .overrideWith(_StubStoreListNotifier.new),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () => showCrossStoreAvailabilitySheet(
                context: ctx,
                productId: 'p1',
                productName: 'Produit A',
              ),
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
  }

  group('CrossStoreAvailabilityBottomSheet (Story 3.4)', () {
    testWidgets('shows store names with quantities after loading',
        (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('Boutique Centre'), findsOneWidget);
      expect(find.text('Entrepôt Nord'), findsOneWidget);
      expect(find.text('15'), findsOneWidget);
    });

    testWidgets('out-of-stock entry shows dash and En rupture', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // AC1: qty=0 stores show '-' indicator and 'En rupture'
      expect(find.text('-'), findsOneWidget);
      expect(find.text('En rupture'), findsOneWidget);
    });

    testWidgets('OWNER sees transfer button', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet(role: 'OWNER'));
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('Initier un transfert'), findsOneWidget);
    });

    testWidgets('EMPLOYEE does not see transfer button', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet(role: 'EMPLOYEE'));
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('Initier un transfert'), findsNothing);
    });

    testWidgets('shows product name in sheet header', (tester) async {
      _setPhoneSize(tester);
      await tester.pumpWidget(_buildSheet());
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      expect(find.text('Produit A'), findsWidgets);
    });

    testWidgets('transfer button disabled when all stores have qty=0',
        (tester) async {
      _setPhoneSize(tester);
      final allZeroModel = CrossStoreAvailabilityModel(
        productId: 'p1',
        productName: 'Produit A',
        entries: [
          CrossStoreAvailabilityEntry(
            storeId: 's1',
            storeName: 'Boutique Centre',
            quantity: 0,
          ),
          CrossStoreAvailabilityEntry(
            storeId: 's2',
            storeName: 'Entrepôt Nord',
            storeType: 'WAREHOUSE',
            quantity: 0,
          ),
        ],
        refreshedAt: DateTime(2024, 1, 1),
      );
      await tester.pumpWidget(_buildSheet(modelOverride: allZeroModel));
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      // Button should be rendered but disabled (onPressed == null)
      final button = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(button.onPressed, isNull);
    });
  });
}

