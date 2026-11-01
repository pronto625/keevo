import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo/features/settings/domain/model/subscription_info.dart';
import 'package:keevo/features/settings/presentation/provider/subscription_info_provider.dart';
import 'package:keevo/features/settings/presentation/widget/plan_limit_bottom_sheet.dart';

void main() {
  group('PlanLimitBottomSheet', () {
    testWidgets('shows entity limit message for stores', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                context: ctx,
                entity: 'stores',
                limit: 1,
              ),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();

      expect(find.textContaining('1 boutique'), findsOneWidget);
      expect(find.text('Passer au plan Premium'), findsOneWidget);
    });

    testWidgets('shows entity limit message for products', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                context: ctx,
                entity: 'products',
                limit: 500,
              ),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();

      expect(find.textContaining('500 produits'), findsOneWidget);
    });

    testWidgets('tapping Premium CTA closes the sheet', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                context: ctx,
                entity: 'stores',
                limit: 1,
              ),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Passer au plan Premium'));
      await tester.pumpAndSettle();
      expect(find.text('Passer au plan Premium'), findsNothing);
    });

    // 16.8: limits are configured server-side — no number is ever guessed.
    for (final (entity, label) in [
      ('employees', "nombre maximal d'employés"),
      ('stores', 'nombre maximal de boutiques'),
      ('products', 'nombre maximal de produits'),
    ]) {
      testWidgets('without a known limit, a sentence without a number for $entity',
          (tester) async {
        await tester.pumpWidget(MaterialApp(
          home: Builder(
            builder: (ctx) => Scaffold(
              body: ElevatedButton(
                onPressed: () =>
                    showPlanLimitBottomSheet(context: ctx, entity: entity),
                child: const Text('test'),
              ),
            ),
          ),
        ));
        await tester.tap(find.text('test'));
        await tester.pumpAndSettle();

        expect(find.textContaining(label), findsOneWidget);
        expect(find.textContaining(RegExp(r'\d')), findsNothing);
      });
    }

    testWidgets('403 PLAN_LIMIT_EXCEEDED limit 2 employees → the sheet says 2',
        (tester) async {
      final error = DioException(
        requestOptions: RequestOptions(path: '/api/v1/employees'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/v1/employees'),
          statusCode: 403,
          data: {
            'domainCode': 'PLAN_LIMIT_EXCEEDED',
            'details': {'entity': 'employees', 'limit': 2, 'current': 2},
          },
        ),
      );
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                  context: ctx,
                  entity: 'employees',
                  limit: planLimitFromError(error)),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();

      expect(find.textContaining('limite de 2 employés'), findsOneWidget);
      expect(find.textContaining('3'), findsNothing);
    });

    test('planLimitFromError reads details.limit of PLAN_LIMIT_EXCEEDED only', () {
      expect(
          planLimitFromError({
            'domainCode': 'PLAN_LIMIT_EXCEEDED',
            'details': {'limit': 4}
          }),
          4);
      expect(planLimitFromError({'domainCode': 'PLAN_LIMIT_EXCEEDED'}), isNull);
      expect(
          planLimitFromError({
            'domainCode': 'FORBIDDEN',
            'details': {'limit': 4}
          }),
          isNull);
      expect(planLimitFromError(Exception('PLAN_LIMIT_EXCEEDED')), isNull);
      expect(planLimitFromError(null), isNull);
    });

    test('subscriptionPlanLimit maps the subscription info limits', () {
      const info = SubscriptionInfo(
        planType: 'FREE',
        status: 'ACTIVE',
        currentStores: 1,
        maxStores: 1,
        currentProducts: 3,
        maxProducts: 500,
        currentEmployees: 2,
        maxEmployees: 2,
      );
      expect(subscriptionPlanLimit(info, 'employees'), 2);
      expect(subscriptionPlanLimit(info, 'products'), 500);
      expect(subscriptionPlanLimit(info, 'stores'), 1);
      expect(subscriptionPlanLimit(null, 'stores'), isNull);
    });
  
    Future<void> openSheet(WidgetTester tester, String? planType) async {
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (ctx) => Scaffold(
            body: ElevatedButton(
              onPressed: () => showPlanLimitBottomSheet(
                  context: ctx, entity: 'stores', limit: 3, planType: planType),
              child: const Text('test'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('test'));
      await tester.pumpAndSettle();
    }

    for (final plan in ['FREE', 'PREMIUM_TRIAL', null]) {
      testWidgets('upgrade offer for plan $plan', (tester) async {
        await openSheet(tester, plan);
        expect(find.byKey(const Key('plan_upgrade_cta')), findsOneWidget);
        expect(find.byKey(const Key('plan_limit_support')), findsNothing);
      });
    }

    testWidgets('a Premium tenant gets the support wording, no upgrade offer',
        (tester) async {
      await openSheet(tester, 'PREMIUM');
      expect(find.byKey(const Key('plan_upgrade_cta')), findsNothing);
      expect(find.text('Passer au plan Premium'), findsNothing);
      expect(find.textContaining('Contactez le support Keevo'), findsOneWidget);
      await tester.tap(find.text('Compris'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('plan_limit_support')), findsNothing);
    });

    test('isPlanLimitError / planTypeFromError read the server body', () {
      final body = {
        'domainCode': 'PLAN_LIMIT_EXCEEDED',
        'details': {'limit': 2, 'plan': 'PREMIUM'},
      };
      final dio = DioException(
        requestOptions: RequestOptions(path: '/x'),
        response:
            Response(requestOptions: RequestOptions(path: '/x'), data: body),
      );
      expect(isPlanLimitError(dio), isTrue);
      expect(isPlanLimitError(body), isTrue);
      expect(isPlanLimitError(Exception('PLAN_LIMIT_EXCEEDED')), isFalse);
      expect(planTypeFromError(dio), 'PREMIUM');
      expect(planTypeFromError({'domainCode': 'FORBIDDEN'}), isNull);
    });

    testWidgets(
        'knownPlanLimit / knownPlanType fall back to the loaded subscription info',
        (tester) async {
      const info = SubscriptionInfo(
        planType: 'PREMIUM',
        status: 'ACTIVE',
        currentStores: 3,
        maxStores: 3,
        currentProducts: 10,
        currentEmployees: 0,
      );
      int? limit;
      String? plan;
      int? fromError;
      await tester.pumpWidget(ProviderScope(
        overrides: [subscriptionInfoProvider.overrideWith((ref) async => info)],
        child: MaterialApp(
          home: Consumer(builder: (context, ref, _) {
            final loaded = ref.watch(subscriptionInfoProvider);
            if (loaded.hasValue) {
              limit = knownPlanLimit(ref, Exception('refused'), 'stores');
              plan = knownPlanType(ref, null);
              fromError = knownPlanLimit(
                  ref,
                  {
                    'domainCode': 'PLAN_LIMIT_EXCEEDED',
                    'details': {'limit': 5}
                  },
                  'stores');
            }
            return const SizedBox();
          }),
        ),
      ));
      await tester.pumpAndSettle();

      expect(limit, 3);
      expect(plan, 'PREMIUM');
      expect(fromError, 5, reason: 'the server number wins');
    });

    testWidgets('knownPlanLimit never fetches the subscription by itself',
        (tester) async {
      int? limit = -1;
      await tester.pumpWidget(ProviderScope(
        child: MaterialApp(
          home: Consumer(builder: (context, ref, _) {
            limit = knownPlanLimit(ref, null, 'stores');
            return const SizedBox();
          }),
        ),
      ));
      expect(limit, isNull);
    });
  });
}
