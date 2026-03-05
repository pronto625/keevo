import '../model/onboarding_result.dart';
import '../model/sector_type.dart';

/// OnboardingRepository — Port (output boundary) between the domain and the
/// data layer.
///
/// Implemented by [OnboardingRepositoryImpl] in the data package.
abstract interface class OnboardingRepository {
  /// Calls POST /api/v1/onboarding/complete with [sectorType] and [storeName].
  ///
  /// Throws [OnboardingException] on API errors.
  Future<OnboardingResult> completeOnboarding({
    required SectorType sectorType,
    required String storeName,
  });
}
