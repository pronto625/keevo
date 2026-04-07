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

      expect(find.text('Gratuit'), findsOneWidget);
      expect(find.text('Boutiques'), findsOneWidget);
      expect(find.text('1 / 1'), findsOneWidget);
      expect(find.text('Produits'), findsOneWidget);
      expect(find.text('87 / 500'), findsOneWidget);
      expect(find.text('Employés'), findsOneWidget);
      expect(find.text('2 / 3'), findsOneWidget);
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
      expect(find.textContaining('Expire'), findsOneWidget);
      expect(find.textContaining('∞'), findsWidgets);
    });
  });
}
