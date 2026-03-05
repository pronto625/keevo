import '../model/onboarding_result.dart';
import '../model/sector_type.dart';
import '../repository/onboarding_repository.dart';

/// CompleteOnboardingUseCase — Application use case that delegates to
/// [OnboardingRepository] to complete the merchant onboarding wizard.
///
/// Single public method [execute] follows the command pattern used by all
/// use cases in this project.
class CompleteOnboardingUseCase {
  final OnboardingRepository _repository;

  const CompleteOnboardingUseCase(this._repository);

  /// Executes the complete-onboarding command.
  ///
  /// Throws [OnboardingException] propagated from the repository on failure.
  Future<OnboardingResult> execute({
    required SectorType sectorType,
    required String storeName,
  }) {
    return _repository.completeOnboarding(
      sectorType: sectorType,
      storeName: storeName,
    );
  }
}
