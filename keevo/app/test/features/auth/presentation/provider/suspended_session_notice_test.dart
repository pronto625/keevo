import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/widget/notice_throttle.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';

/// v1s-16-13: N concurrent SESSION_REVOKED → exactly one SnackBar.
void main() {
  testWidgets('5 reports in the window show one SnackBar', (tester) async {
    final key = GlobalKey<ScaffoldMessengerState>();
    await tester.pumpWidget(MaterialApp(
      scaffoldMessengerKey: key,
      home: const Scaffold(body: SizedBox()),
    ));

    final throttle = NoticeThrottle();
    for (var i = 0; i < 5; i++) {
      showSuspendedSessionNotice(throttle, key.currentState);
    }
    await tester.pump();

    expect(find.byType(SnackBar), findsOneWidget);
    expect(find.text('Votre accès a été suspendu, veuillez vous reconnecter.'),
        findsOneWidget);

    // Nothing queued behind it.
    for (var i = 0; i < 3; i++) {
      await tester.pump(const Duration(seconds: 5));
    }
    await tester.pumpAndSettle();
    expect(find.byType(SnackBar), findsNothing);
  });
}
