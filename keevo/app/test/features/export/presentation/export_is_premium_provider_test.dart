import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/export/presentation/provider/export_providers.dart';
import 'package:keevo/features/settings/domain/model/subscription_info.dart';
import 'package:keevo/features/settings/presentation/provider/subscription_info_provider.dart';

/// v1s-18-4 — plan used by the format badges: Premium only when active,
/// unknown (null) when the subscription cannot be read.
void main() {
  Future<bool?> premiumFor(Future<SubscriptionInfo> Function() info) async {
    final container = ProviderContainer(overrides: [
      subscriptionInfoProvider.overrideWith((_) => info()),
    ]);
    addTearDown(container.dispose);
    final sub = container.listen(exportIsPremiumProvider, (_, __) {});
    addTearDown(sub.close);
    return container.read(exportIsPremiumProvider.future);
  }

  SubscriptionInfo info(String plan, String status) => SubscriptionInfo(
        planType: plan,
        status: status,
        currentStores: 1,
        currentProducts: 0,
        currentEmployees: 0,
      );

  test('FREE active → false', () async {
    expect(await premiumFor(() async => info('FREE', 'ACTIVE')), isFalse);
  });

  test('PREMIUM active → true', () async {
    expect(await premiumFor(() async => info('PREMIUM', 'ACTIVE')), isTrue);
  });

  test('PREMIUM expired → false', () async {
    expect(await premiumFor(() async => info('PREMIUM', 'EXPIRED')), isFalse);
  });

  test('subscription unavailable → null', () async {
    expect(await premiumFor(() async => throw Exception('offline')), isNull);
  });
}
