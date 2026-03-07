import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/settings/presentation/provider/account_status_provider.dart';
import 'package:keevo/features/settings/presentation/widget/suspension_banner.dart';

void main() {
  group('SuspensionBanner', () {
    testWidgets('shows suspension message when account manually suspended by admin',
        (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          accountStatusProvider.overrideWith(
            (ref) => AccountStatus.suspended,
          ),
        ],
        child: const MaterialApp(
          home: Scaffold(body: SuspensionBanner()),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('compte est suspendu'), findsOneWidget);
    });

    testWidgets(
        'shows trial expiry banner when trial expired (downgraded to Free)',
        (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          accountStatusProvider.overrideWith(
            (ref) => AccountStatus.trialExpired,
          ),
        ],
        child: const MaterialApp(
          home: Scaffold(body: SuspensionBanner()),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining("période d'essai est terminée"), findsOneWidget);
    });

    testWidgets('hides banner when account is active', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          accountStatusProvider.overrideWith(
            (ref) => AccountStatus.active,
          ),
        ],
        child: const MaterialApp(
          home: Scaffold(body: SuspensionBanner()),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('suspendu'), findsNothing);
      expect(find.textContaining('essai'), findsNothing);
    });
  });
}
