import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';

import 'package:keevo/features/onboarding/domain/model/sector_type.dart';
import 'package:keevo/features/onboarding/presentation/page/sector_selection_page.dart';
import 'package:keevo/features/onboarding/presentation/page/shop_name_page.dart';

// ── Helpers ──────────────────────────────────────────────────────────────────

GoRouter _testRouter() {
  return GoRouter(
    initialLocation: '/onboarding/sector',
    routes: [
      GoRoute(
        path: '/onboarding/sector',
        builder: (_, __) => const SectorSelectionPage(),
      ),
      GoRoute(
        path: '/onboarding/shop-name',
        builder: (context, state) {
          final sector = state.extra as SectorType;
          return ShopNamePage(sectorType: sector);
        },
      ),
    ],
  );
}

Widget _buildPage() {
  return ProviderScope(
    child: MaterialApp.router(
      routerConfig: _testRouter(),
    ),
  );
}

// ── Tests ────────────────────────────────────────────────────────────────────

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  group('SectorSelectionPage', () {
    testWidgets('renders sector grid and continue button', (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pump();

      expect(find.byKey(const Key('sectorGrid')), findsOneWidget);
      expect(find.byKey(const Key('continueButton')), findsOneWidget);
    });

    testWidgets('continue button is disabled initially', (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pump();

      final button = tester.widget<FilledButton>(
        find.byKey(const Key('continueButton')),
      );
      expect(button.onPressed, isNull);
    });

    testWidgets('all 8 sector tiles are rendered', (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pump();

      // Scroll until each tile is visible (GridView is lazy)
      for (final sector in SectorType.values) {
        await tester.scrollUntilVisible(
          find.byKey(Key('sectorTile_${sector.apiCode}')),
          100,
          scrollable: find.byType(Scrollable).first,
        );
        expect(
          find.byKey(Key('sectorTile_${sector.apiCode}')),
          findsOneWidget,
          reason: 'Missing tile for ${sector.apiCode}',
        );
      }
    });

    testWidgets('tapping a sector tile enables the continue button',
        (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pump();

      await tester.tap(
        find.byKey(Key('sectorTile_${SectorType.clothing.apiCode}')),
      );
      await tester.pump();

      final button = tester.widget<FilledButton>(
        find.byKey(const Key('continueButton')),
      );
      expect(button.onPressed, isNotNull);
    });

    testWidgets('tapping continue navigates to shop-name page', (tester) async {
      await tester.pumpWidget(_buildPage());
      await tester.pump();

      await tester.tap(
        find.byKey(Key('sectorTile_${SectorType.electronics.apiCode}')),
      );
      await tester.pump();

      await tester.tap(find.byKey(const Key('continueButton')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('shopNameField')), findsOneWidget);
    });
  });
}
