import 'package:freezed_annotation/freezed_annotation.dart';

part 'onboarding_result.freezed.dart';

/// OnboardingResult — Domain model for a successful onboarding response.
///
/// Immutable Freezed value object returned by [CompleteOnboardingUseCase].
/// Mirrors the backend OnboardingResult record.
@freezed
class OnboardingResult with _$OnboardingResult {
  const factory OnboardingResult({
    required String tenantId,
    required String sectorType,
    required String storeName,
    required int categoriesCreated,
  }) = _OnboardingResult;
}
