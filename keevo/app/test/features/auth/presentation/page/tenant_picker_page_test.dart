import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/features/auth/domain/model/membership_dto.dart';
import 'package:keevo/features/auth/presentation/page/tenant_picker_page.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// v1s-16-13: the tenant picker had no exit.
void main() {
  Widget page() => ProviderScope(
        retry: noProviderRetry,
        child: MaterialApp.router(
          routerConfig: GoRouter(routes: [
            GoRoute(
              path: '/',
              builder: (_, __) => const TenantPickerPage(
                loginToken: 'login-token',
                memberships: [
                  MembershipDto(
                      tenantCode: 'KV-A',
                      tenantName: 'Boutique A',
                      role: 'OWNER',
                      schemaName: 'a'),
                  MembershipDto(
                      tenantCode: 'KV-B',
                      tenantName: 'Boutique B',
                      role: 'EMPLOYEE',
                      schemaName: 'b'),
                ],
              ),
            ),
            GoRoute(
              path: '/auth/login',
              builder: (_, __) => const Scaffold(body: Text('Login')),
            ),
          ]),
        ),
      );

  testWidgets('the back button returns to login', (tester) async {
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Retour à la connexion'));
    await tester.pumpAndSettle();

    expect(find.text('Login'), findsOneWidget);
  });

  testWidgets('system back returns to login', (tester) async {
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.text('Login'), findsOneWidget);
  });
}
