/// OnboardingException — Domain exception for onboarding errors.
///
/// Lives in the domain layer so that both data and presentation layers
/// can reference it without violating architectural boundaries.
class OnboardingException implements Exception {
  final String domainCode;
  final String message;

  const OnboardingException({required this.domainCode, required this.message});

  @override
  String toString() => 'OnboardingException[$domainCode]: $message';
}
