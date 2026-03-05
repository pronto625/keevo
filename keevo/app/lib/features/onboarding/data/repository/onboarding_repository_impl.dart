import '../../domain/model/onboarding_result.dart';
import '../../domain/model/sector_type.dart';
import '../../domain/repository/onboarding_repository.dart';
import '../datasource/remote_onboarding_datasource.dart';

/// OnboardingRepositoryImpl — Concrete implementation of [OnboardingRepository].
///
/// Delegates to [RemoteOnboardingDataSource] and re-throws [OnboardingException]
/// unchanged. Future stories may add caching or offline support here.
class OnboardingRepositoryImpl implements OnboardingRepository {
  final RemoteOnboardingDataSource _remoteDataSource;

  const OnboardingRepositoryImpl(this._remoteDataSource);

  @override
  Future<OnboardingResult> completeOnboarding({
    required SectorType sectorType,
    required String storeName,
  }) {
    return _remoteDataSource.completeOnboarding(
      sectorType: sectorType,
      storeName: storeName,
    );
  }
}
