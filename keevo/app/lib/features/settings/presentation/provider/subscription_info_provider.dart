import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/repository/remote_subscription_repository.dart';
import '../../domain/model/subscription_info.dart';
import '../../domain/repository/subscription_repository.dart';

// ── Repository provider ────────────────────────────────────────────────────

/// Provides the [SubscriptionRepository] backed by the remote REST API.
final subscriptionRepositoryProvider = Provider<SubscriptionRepository>((ref) {
  final dio = ref.watch(_subscriptionDioProvider);
  return RemoteSubscriptionRepository(dio: dio);
});

/// Separate Dio provider for subscription calls.
/// Reads from the shared [dioProvider] defined in core/di/providers.dart.
final _subscriptionDioProvider = Provider<Dio>((ref) {
  // Re-use the same Dio instance (already has AuthInterceptor attached).
  // This provider exists for easier testing override.
  return ref.watch(dioProvider);
});

// ── Subscription info FutureProvider ──────────────────────────────────────

/// [subscriptionInfoProvider] — AsyncValue<SubscriptionInfo> from GET /api/v1/subscription/me.
///
/// Usage in widgets: `ref.watch(subscriptionInfoProvider)`
/// Override in tests: `subscriptionInfoProvider.overrideWith((ref) async => fakeData)`
final subscriptionInfoProvider = FutureProvider<SubscriptionInfo>((ref) async {
  final repository = ref.watch(subscriptionRepositoryProvider);
  return repository.getMySubscription();
});
