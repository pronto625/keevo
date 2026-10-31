/// Story 15.4b — Riverpod 3 retries failing providers by default (exponential
/// backoff). Keevo turns that off app-wide (`ProviderScope` in `main.dart`)
/// and in tests (every `ProviderContainer` / `ProviderScope`): several
/// providers rely on "a failure is not cached, the next read asks again"
/// (entitlements, extension slots) and on showing the error immediately.
///
/// Pass it as `retry:`; returning null means "never retry".
Duration? noProviderRetry(int retryCount, Object error) => null;
