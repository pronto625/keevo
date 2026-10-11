import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';

import 'package:keevo/features/auth/presentation/page/reset_password_page.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  testWidgets('opens from the forgot-password flow without a framework error',
      (tester) async {
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => const Scaffold()),
      GoRoute(
        path: '/auth/reset-password',
        builder: (_, state) => ResetPasswordPage(
          phoneNumber: (state.extra as Map<String, dynamic>?)?['phoneNumber']
              as String?,
        ),
      ),
    ]);
    await tester.pumpWidget(
      ProviderScope(child: MaterialApp.router(routerConfig: router)),
    );

    router.push('/auth/reset-password', extra: {'phoneNumber': '+237600000001'});
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.byType(ResetPasswordPage), findsOneWidget);
  });
}
