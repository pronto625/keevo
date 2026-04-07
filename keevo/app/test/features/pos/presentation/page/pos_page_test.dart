import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/sync/sync_status.dart';
import 'package:keevo/core/sync/sync_status_provider.dart';
import 'package:keevo/features/pos/presentation/page/pos_page.dart';
import 'package:keevo/features/pos/presentation/provider/pos_providers.dart';

import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  late SharedPreferences prefs;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });

  Widget buildApp({List<Override> overrides = const []}) {
    return ProviderScope(
      overrides: [
        syncStatusProvider
            .overrideWith((ref) => Stream.value(SyncStatus.online)),
        daysOfflineProvider.overrideWithValue(0),
        sharedPreferencesProvider.overrideWithValue(prefs),
        currentUserRoleProvider.overrideWithValue('OWNER'),
        frequentProductsProvider
            .overrideWith((ref, key) => Future.value([])),
        ...overrides,
      ],
      child: const MaterialApp(home: PosPage()),
    );
  }

  group('PosPage', () {
    testWidgets('displays search bar', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pump();
      expect(find.byType(TextField), findsOneWidget);
      expect(find.text('Chercher un produit'), findsOneWidget);
    });

    testWidgets('displays frequent products grid (empty state)',
        (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pumpAndSettle();
      // With empty products, the empty state shows
      expect(find.text('Votre boutique est prête !'), findsOneWidget);
    });

    testWidgets('cartPill hidden initially (empty cart)', (tester) async {
      await tester.pumpWidget(buildApp());
      await tester.pump();
      // CartPill with 0 items should be hidden (AnimatedSwitcher → SizedBox.shrink)
      // The empty state may show an "Ajouter un produit" button, so check for "Encaisser" text
      expect(find.textContaining('Encaisser'), findsNothing);
    });

    testWidgets('cartPill visible after adding product', (tester) async {
      await tester.pumpWidget(buildApp(
        overrides: [
          frequentProductsProvider.overrideWith((ref, key) => Future.value([
                const PosProductResult(
                  id: 'p1',
                  name: 'Savon',
                  price: 500,
                  stock: 10,
                ),
              ])),
        ],
      ));
      await tester.pumpAndSettle();

      // Tap the product card to add to cart
      await tester.tap(find.text('Savon'));
      await tester.pumpAndSettle();

      // CartPill should now show the "Encaisser" button
      expect(find.textContaining('ENCAISSER'), findsOneWidget);
    });

    testWidgets('haptic feedback on product tap', (tester) async {
      final log = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          log.add(methodCall);
          return null;
        },
      );

      await tester.pumpWidget(buildApp(
        overrides: [
          frequentProductsProvider.overrideWith((ref, key) => Future.value([
                const PosProductResult(
                  id: 'p1',
                  name: 'Savon',
                  price: 500,
                  stock: 10,
                ),
              ])),
        ],
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Savon'));
      await tester.pump();

      expect(
        log.any((c) =>
            c.method == 'HapticFeedback.vibrate'),
        isTrue,
      );

      // Clear the 150ms flash timer from ProductCard
      await tester.pump(const Duration(milliseconds: 200));
    });
  });
}
