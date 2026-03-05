import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../data/datasource/remote_onboarding_datasource.dart';
import '../../data/repository/onboarding_repository_impl.dart';
import '../../domain/model/onboarding_result.dart';
import '../../domain/model/sector_type.dart';
import '../../domain/repository/onboarding_repository.dart';
import '../../domain/usecase/complete_onboarding_usecase.dart';
import '../../../auth/presentation/provider/auth_provider.dart';

part 'onboarding_provider.g.dart';

// ── Repository provider ────────────────────────────────────────────────────

final remoteOnboardingDataSourceProvider =
    Provider<RemoteOnboardingDataSource>((ref) {
  return RemoteOnboardingDataSource(dio: ref.watch(dioProvider));
});

final onboardingRepositoryProvider = Provider<OnboardingRepository>((ref) {
  return OnboardingRepositoryImpl(
    ref.watch(remoteOnboardingDataSourceProvider),
  );
});

final completeOnboardingUseCaseProvider =
    Provider<CompleteOnboardingUseCase>((ref) {
  return CompleteOnboardingUseCase(ref.watch(onboardingRepositoryProvider));
});

// ── Onboarding AsyncNotifier (Riverpod code-gen) ──────────────────────────

/// [Onboarding] manages the async complete-onboarding lifecycle.
///
/// State: [AsyncValue<OnboardingResult?>]
/// - Initial / reset: AsyncData(null)
/// - Loading:         AsyncLoading()
/// - Success:         AsyncData(OnboardingResult(...))
/// - Error:           AsyncError(OnboardingException, stackTrace)
@riverpod
class Onboarding extends _$Onboarding {
  @override
  FutureOr<OnboardingResult?> build() => null;

  Future<void> complete({
    required SectorType sectorType,
    required String storeName,
  }) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(completeOnboardingUseCaseProvider).execute(
            sectorType: sectorType,
            storeName: storeName,
          ),
    );
  }

  /// Reset state to initial (e.g. after navigation).
  void reset() => state = const AsyncData(null);
}
