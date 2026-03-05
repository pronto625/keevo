import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/onboarding/domain/exception/onboarding_exception.dart';
import 'package:keevo/features/onboarding/domain/model/onboarding_result.dart';
import 'package:keevo/features/onboarding/domain/model/sector_type.dart';
import 'package:keevo/features/onboarding/domain/repository/onboarding_repository.dart';
import 'package:keevo/features/onboarding/domain/usecase/complete_onboarding_usecase.dart';

// ── Mocks ────────────────────────────────────────────────────────────────────

class MockOnboardingRepository extends Mock implements OnboardingRepository {}

// ── Tests ────────────────────────────────────────────────────────────────────

void main() {
  setUpAll(() {
    registerFallbackValue(SectorType.other); // required by mocktail for any(named:...)
  });
  late MockOnboardingRepository mockRepo;
  late CompleteOnboardingUseCase useCase;

  setUp(() {
    mockRepo = MockOnboardingRepository();
    useCase = CompleteOnboardingUseCase(mockRepo);
  });

  group('CompleteOnboardingUseCase', () {
    const result = OnboardingResult(
      tenantId: 'tenant-uuid',
      sectorType: 'CLOTHING',
      storeName: 'Boutique Test',
      categoriesCreated: 13,
    );

    test('delegates to repository with correct parameters', () async {
      when(() => mockRepo.completeOnboarding(
                sectorType: SectorType.clothing,
                storeName: 'Boutique Test',
              ))
          .thenAnswer((_) async => result);

      final actual = await useCase.execute(
        sectorType: SectorType.clothing,
        storeName: 'Boutique Test',
      );

      expect(actual, equals(result));
      verify(() => mockRepo.completeOnboarding(
            sectorType: SectorType.clothing,
            storeName: 'Boutique Test',
          )).called(1);
    });

    test('propagates OnboardingException from repository', () async {
      when(() => mockRepo.completeOnboarding(
                sectorType: any(named: 'sectorType'),
                storeName: any(named: 'storeName'),
              ))
          .thenThrow(const OnboardingException(
            domainCode: 'SECTOR_TEMPLATE_NOT_FOUND',
            message: 'Unknown sector',
          ));

      expect(
        () => useCase.execute(
          sectorType: SectorType.other,
          storeName: 'Test',
        ),
        throwsA(isA<OnboardingException>()),
      );
    });

    test('passes each SectorType through to the repository', () async {
      for (final sector in SectorType.values) {
        when(() => mockRepo.completeOnboarding(
                  sectorType: sector,
                  storeName: any(named: 'storeName'),
                ))
            .thenAnswer((_) async => OnboardingResult(
                  tenantId: 'tid',
                  sectorType: sector.apiCode,
                  storeName: 'S',
                  categoriesCreated: 3,
                ));

        final r = await useCase.execute(sectorType: sector, storeName: 'S');
        expect(r.sectorType, equals(sector.apiCode));
      }
    });
  });
}
