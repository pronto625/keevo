import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/settings/domain/model/subscription_info.dart';
import 'package:keevo/features/settings/presentation/page/subscription_page.dart';
import 'package:keevo/features/settings/presentation/provider/subscription_info_provider.dart';

void main() {
  group('SubscriptionPage', () {
    testWidgets('shows Free plan type, status and usage counts with limits',
        (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          subscriptionInfoProvider.overrideWith(
            (ref) async => const SubscriptionInfo(
              planType: 'FREE',
              status: 'ACTIVE',
              currentStores: 1,
              maxStores: 1,
              currentProducts: 87,
              maxProducts: 500,
              currentEmployees: 2,
              maxEmployees: 3,
            ),
          ),
        ],
        child: const MaterialApp(home: SubscriptionPage()),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Plan gratuit'), findsOneWidget);
      expect(find.text('1/1 boutique'), findsOneWidget);
      expect(find.text('87/500 produits'), findsOneWidget);
      expect(find.text('2/3 employés'), findsOneWidget);
    });

    testWidgets(
        'shows Premium Trial plan with expiry date and unlimited label',
        (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          subscriptionInfoProvider.overrideWith(
            (ref) async => const SubscriptionInfo(
              planType: 'PREMIUM_TRIAL',
              status: 'ACTIVE',
              expiresAt: '2026-09-06T00:00:00Z',
              currentStores: 3,
              maxStores: null,
              currentProducts: 450,
              maxProducts: null,
              currentEmployees: 5,
              maxEmployees: null,
            ),
          ),
        ],
        child: const MaterialApp(home: SubscriptionPage()),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Premium Trial'), findsOneWidget);
      expect(find.textContaining('Expire le'), findsOneWidget);
      expect(find.textContaining('illimité'), findsWidgets);
    });
  });
}
