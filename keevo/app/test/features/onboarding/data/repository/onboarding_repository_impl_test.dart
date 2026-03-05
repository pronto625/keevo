import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/onboarding/data/datasource/remote_onboarding_datasource.dart';
import 'package:keevo/features/onboarding/data/repository/onboarding_repository_impl.dart';
import 'package:keevo/features/onboarding/domain/model/onboarding_result.dart';
import 'package:keevo/features/onboarding/domain/model/sector_type.dart';

// ── Mocks ────────────────────────────────────────────────────────────────────

class MockRemoteOnboardingDataSource extends Mock
    implements RemoteOnboardingDataSource {}

// ── Tests ────────────────────────────────────────────────────────────────────

void main() {
  setUpAll(() {
    registerFallbackValue(SectorType.other); // required by mocktail for any(named:...)
  });
  late MockRemoteOnboardingDataSource mockDataSource;
  late OnboardingRepositoryImpl repository;

  setUp(() {
    mockDataSource = MockRemoteOnboardingDataSource();
    repository = OnboardingRepositoryImpl(mockDataSource);
  });

  group('OnboardingRepositoryImpl', () {
    const result = OnboardingResult(
      tenantId: 'tenant-uuid',
      sectorType: 'ELECTRONICS',
      storeName: 'Tech Shop',
      categoriesCreated: 14,
    );

    test('delegates completeOnboarding call to remote datasource', () async {
      when(() => mockDataSource.completeOnboarding(
                sectorType: SectorType.electronics,
                storeName: 'Tech Shop',
              ))
          .thenAnswer((_) async => result);

      final actual = await repository.completeOnboarding(
        sectorType: SectorType.electronics,
        storeName: 'Tech Shop',
      );

      expect(actual, equals(result));
      verify(() => mockDataSource.completeOnboarding(
            sectorType: SectorType.electronics,
            storeName: 'Tech Shop',
          )).called(1);
    });

    test('propagates exceptions from datasource unchanged', () {
      when(() => mockDataSource.completeOnboarding(
                sectorType: any(named: 'sectorType'),
                storeName: any(named: 'storeName'),
              ))
          .thenThrow(Exception('Network error'));

      expect(
        () => repository.completeOnboarding(
          sectorType: SectorType.clothing,
          storeName: 'Test',
        ),
        throwsA(isA<Exception>()),
      );
    });
  });
}
