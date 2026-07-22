import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/auth/domain/repository/token_storage.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/settings/domain/model/subscription_info.dart';
import 'package:keevo/features/settings/presentation/provider/account_status_provider.dart';
import 'package:keevo/features/settings/presentation/provider/subscription_info_provider.dart';
import 'package:mocktail/mocktail.dart';

class MockTokenStorage extends Mock implements TokenStorage {}

/// Helper: builds a JWT-like string with the given payload claims.
/// The payload is base64Url-encoded as a JWT part 2 would be.
String _jwtWith(Map<String, dynamic> claims) {
  final payload = base64Url.encode(utf8.encode(jsonEncode(claims)));
  return 'eyJhbGciOiJIUzI1NiJ9.$payload.fakeSignature';
}

void main() {
  late MockTokenStorage mockTokenStorage;

  setUp(() {
    mockTokenStorage = MockTokenStorage();
  });

  group('accountStatusProvider derivation', () {
    /// Helper: creates a fresh container with the given overrides and waits for
    /// all async FutureProviders to settle before returning.
    Future<ProviderContainer> settledContainer({
      required String? token,
      required SubscriptionInfo subscription,
    }) async {
      when(() => mockTokenStorage.getToken()).thenAnswer((_) async => token);

      final c = ProviderContainer(overrides: [
        tokenStorageProvider.overrideWith((ref) => mockTokenStorage),
        subscriptionInfoProvider.overrideWith((ref) async => subscription),
      ]);

      await c.read(tenantStatusClaimProvider.future);
      await c.read(subscriptionInfoProvider.future);

      return c;
    }

    test('returns suspended when JWT tenantStatus claim is SUSPENDED',
        () async {
      final c = await settledContainer(
        token: _jwtWith({'tenantStatus': 'SUSPENDED', 'sub': 'user-1'}),
        subscription: const SubscriptionInfo(
          planType: 'PREMIUM',
          status: 'ACTIVE',
          currentStores: 1,
          currentProducts: 10,
          currentEmployees: 0,
        ),
      );
      addTearDown(c.dispose);

      final status = c.read(accountStatusProvider);
      expect(status, AccountStatus.suspended);
    });

    test(
        'returns suspended when JWT claims SUSPENDED regardless of planType',
        () async {
      final c = await settledContainer(
        token: _jwtWith({'tenantStatus': 'SUSPENDED'}),
        subscription: const SubscriptionInfo(
          planType: 'FREE',
          status: 'EXPIRED',
          currentStores: 1,
          currentProducts: 10,
          currentEmployees: 0,
        ),
      );
      addTearDown(c.dispose);

      final status = c.read(accountStatusProvider);
      expect(status, AccountStatus.suspended);
    });

    test('returns trialExpired when tenantStatus=ACTIVE and planType=FREE',
        () async {
      final c = await settledContainer(
        token: _jwtWith({'tenantStatus': 'ACTIVE'}),
        subscription: const SubscriptionInfo(
          planType: 'FREE',
          status: 'EXPIRED',
          currentStores: 1,
          currentProducts: 10,
          currentEmployees: 0,
        ),
      );
      addTearDown(c.dispose);

      final status = c.read(accountStatusProvider);
      expect(status, AccountStatus.trialExpired);
    });

    test(
        'returns active when tenantStatus=ACTIVE and planType=PREMIUM_TRIAL',
        () async {
      final c = await settledContainer(
        token: _jwtWith({'tenantStatus': 'ACTIVE'}),
        subscription: const SubscriptionInfo(
          planType: 'PREMIUM_TRIAL',
          status: 'ACTIVE',
          currentStores: 1,
          currentProducts: 10,
          currentEmployees: 0,
        ),
      );
      addTearDown(c.dispose);

      final status = c.read(accountStatusProvider);
      expect(status, AccountStatus.active);
    });

    test('returns active when token is absent (safe fallback)', () async {
      final c = await settledContainer(
        token: null,
        subscription: const SubscriptionInfo(
          planType: 'PREMIUM_TRIAL',
          status: 'ACTIVE',
          currentStores: 1,
          currentProducts: 10,
          currentEmployees: 0,
        ),
      );
      addTearDown(c.dispose);

      final status = c.read(accountStatusProvider);
      expect(status, AccountStatus.active);
    });

    test('returns active on decode error (safe fallback)', () async {
      // 3 parts (passes the parts.length guard) but the payload segment is not
      // valid base64Url, so decoding throws and hits the try/catch fallback.
      when(() => mockTokenStorage.getToken())
          .thenAnswer((_) async => 'eyJhbGciOiJIUzI1NiJ9.!!!not-base64!!!.sig');

      final c = ProviderContainer(overrides: [
        tokenStorageProvider.overrideWith((ref) => mockTokenStorage),
        subscriptionInfoProvider.overrideWith(
          (ref) async => const SubscriptionInfo(
            planType: 'PREMIUM_TRIAL',
            status: 'ACTIVE',
            currentStores: 1,
            currentProducts: 10,
            currentEmployees: 0,
          ),
        ),
      ]);
      addTearDown(c.dispose);

      // Wait for tenantStatusClaimProvider to resolve (it'll return null on decode error)
      await c.read(tenantStatusClaimProvider.future);
      await c.read(subscriptionInfoProvider.future);

      final status = c.read(accountStatusProvider);
      expect(status, AccountStatus.active);
    });

    test('returns active on subscription error (safe fallback)', () async {
      final token = _jwtWith({'tenantStatus': 'ACTIVE'});
      when(() => mockTokenStorage.getToken()).thenAnswer((_) async => token);

      final c = ProviderContainer(overrides: [
        tokenStorageProvider.overrideWith((ref) => mockTokenStorage),
        subscriptionInfoProvider.overrideWith(
          (ref) async => throw Exception('network error'),
        ),
      ]);
      addTearDown(c.dispose);

      // Wait for the tenantStatusClaimProvider to resolve (ACTIVE → not suspended)
      await c.read(tenantStatusClaimProvider.future);
      // Explicitly await the subscription error settling to AsyncError before
      // asserting — otherwise the read below could race ahead while
      // subscriptionInfoProvider is still AsyncLoading, making this test pass
      // regardless of whether the error-handling branch actually works.
      await expectLater(
        c.read(subscriptionInfoProvider.future),
        throwsA(isException),
      );

      final status = c.read(accountStatusProvider);
      expect(status, AccountStatus.active);
    });
  });
}
