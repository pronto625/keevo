import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/onboarding/domain/exception/onboarding_exception.dart';
import 'package:keevo/features/onboarding/domain/model/onboarding_result.dart';
import 'package:keevo/features/onboarding/domain/model/sector_type.dart';
import 'package:keevo/features/onboarding/domain/usecase/complete_onboarding_usecase.dart';
import 'package:keevo/features/onboarding/presentation/page/shop_name_page.dart';
import 'package:keevo/features/onboarding/presentation/provider/onboarding_provider.dart';

// ── Mocks ────────────────────────────────────────────────────────────────────

class MockCompleteOnboardingUseCase extends Mock
    implements CompleteOnboardingUseCase {}

// ── Helpers ──────────────────────────────────────────────────────────────────

GoRouter _testRouter(SectorType sector) {
  return GoRouter(
    initialLocation: '/onboarding/shop-name',
    routes: [
      GoRoute(
        path: '/onboarding/shop-name',
        builder: (_, __) => ShopNamePage(sectorType: sector),
      ),
      GoRoute(
        path: '/pos',
        builder: (_, __) => const Scaffold(body: Text('POS')),
      ),
    ],
  );
}

Widget _buildPage(
  SectorType sector,
  List<Override> overrides,
) {
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp.router(
      routerConfig: _testRouter(sector),
    ),
  );
}

// ── Tests ────────────────────────────────────────────────────────────────────

void main() {
  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
    registerFallbackValue(SectorType.other); // required by mocktail for any(named:...)
  });

  late MockCompleteOnboardingUseCase mockUseCase;

  setUp(() {
    mockUseCase = MockCompleteOnboardingUseCase();
  });

  group('ShopNamePage', () {
    testWidgets('renders shop name field and submit button', (tester) async {
      await tester.pumpWidget(
        _buildPage(SectorType.clothing, [
          completeOnboardingUseCaseProvider.overrideWithValue(mockUseCase),
        ]),
      );
      await tester.pump();

      expect(find.byKey(const Key('shopNameField')), findsOneWidget);
      expect(find.byKey(const Key('submitButton')), findsOneWidget);
    });

    testWidgets('shows validation error when submitted with empty name',
        (tester) async {
      await tester.pumpWidget(
        _buildPage(SectorType.clothing, [
          completeOnboardingUseCaseProvider.overrideWithValue(mockUseCase),
        ]),
      );
      await tester.pump();

      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pumpAndSettle();

      expect(find.text('Le nom du commerce est requis'), findsOneWidget);
    });

    testWidgets('shows validation error when name is too short', (tester) async {
      await tester.pumpWidget(
        _buildPage(SectorType.clothing, [
          completeOnboardingUseCaseProvider.overrideWithValue(mockUseCase),
        ]),
      );
      await tester.pump();

      await tester.enterText(find.byKey(const Key('shopNameField')), 'A');
      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pumpAndSettle();

      expect(
        find.text('Le nom doit contenir au moins 2 caractères'),
        findsOneWidget,
      );
    });

    testWidgets('shows error snackbar when API returns error', (tester) async {
      when(() => mockUseCase.execute(
                sectorType: any(named: 'sectorType'),
                storeName: any(named: 'storeName'),
              ))
          .thenThrow(const OnboardingException(
            domainCode: 'SECTOR_TEMPLATE_NOT_FOUND',
            message: 'Unknown sector',
          ));

      await tester.pumpWidget(
        _buildPage(SectorType.clothing, [
          completeOnboardingUseCaseProvider.overrideWithValue(mockUseCase),
        ]),
      );
      await tester.pump();

      await tester.enterText(
          find.byKey(const Key('shopNameField')), 'Ma Boutique');
      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.byKey(const Key('errorSnackBar')), findsOneWidget);
    });

    testWidgets('calls use case with correct sector and store name',
        (tester) async {
      when(() => mockUseCase.execute(
                sectorType: SectorType.electronics,
                storeName: 'Tech World',
              ))
          .thenAnswer((_) async => const OnboardingResult(
                tenantId: 'tid',
                sectorType: 'ELECTRONICS',
                storeName: 'Tech World',
                categoriesCreated: 14,
              ));

      await tester.pumpWidget(
        _buildPage(SectorType.electronics, [
          completeOnboardingUseCaseProvider.overrideWithValue(mockUseCase),
        ]),
      );
      await tester.pump();

      await tester.enterText(
          find.byKey(const Key('shopNameField')), 'Tech World');
      await tester.tap(find.byKey(const Key('submitButton')));
      await tester.pump();

      verify(() => mockUseCase.execute(
            sectorType: SectorType.electronics,
            storeName: 'Tech World',
          )).called(1);
    });
  });
}
