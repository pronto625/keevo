import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/features/auth/domain/model/account_profile.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/settings/presentation/page/account_page.dart';

/// Builds AccountPage wrapped with ProviderScope + GoRouter.
///
/// [profile] = null means loading (Completer-based, no pending timer).
/// [error] = non-null means error state.
Widget _buildPage({
  required AccountProfile? profile,
  Object? error,
}) {
  return ProviderScope(
    overrides: [
      accountProfileProvider.overrideWith((_) async {
        if (error != null) throw error;
        if (profile != null) return profile;
        // Loading state: Completer future never fires — no pending timer.
        return Completer<AccountProfile>().future;
      }),
    ],
    child: MaterialApp.router(
      routerConfig: GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => const AccountPage()),
        GoRoute(
          path: '/settings/change-password',
          builder: (_, __) => const Scaffold(
            body: Center(child: Text('ChangePasswordPage')),
          ),
        ),
      ]),
    ),
  );
}

void main() {
  group('AccountPage (Story 8.6 AC2)', () {
    testWidgets('loading state — skeleton shown (no profile text)',
        (tester) async {
      await tester.pumpWidget(_buildPage(profile: null));
      await tester.pump(); // one frame — provider is AsyncLoading

      // Profile data should not be shown yet
      expect(find.text('Jean'), findsNothing);
      // No error shown
      expect(find.text('Impossible de charger le profil'), findsNothing);
      // AppBar title is rendered
      expect(find.text('Mon Compte'), findsOneWidget);
    });

    testWidgets('EMPLOYEE — displays firstName, lastName, phone, Employé badge',
        (tester) async {
      final profile = AccountProfile(
        userId: 'u1',
        phoneNumber: '+237600000001',
        role: 'EMPLOYEE',
        firstName: 'Jean',
        lastName: 'Dupont',
        storeId: 's1',
        storeName: 'Boutique Centrale',
      );

      await tester.pumpWidget(_buildPage(profile: profile));
      await tester.pumpAndSettle();

      expect(find.text('Jean'), findsOneWidget);
      expect(find.text('Dupont'), findsOneWidget);
      expect(find.text('+237600000001'), findsOneWidget);
      expect(find.text('Employé'), findsOneWidget);
      expect(find.text('Boutique Centrale'), findsOneWidget);
    });

    testWidgets('OWNER — firstName/lastName = "—", no store section',
        (tester) async {
      final profile = AccountProfile(
        userId: 'u2',
        phoneNumber: '+237600000002',
        role: 'OWNER',
      );

      await tester.pumpWidget(_buildPage(profile: profile));
      await tester.pumpAndSettle();

      expect(find.text('—'), findsNWidgets(2)); // Prénom + Nom both "—"
      expect(find.text('Propriétaire'), findsOneWidget);
      expect(find.text('Boutique assignée'), findsNothing);
    });

    testWidgets('"Changer mon mot de passe" tile is visible', (tester) async {
      final profile = AccountProfile(
        userId: 'u3',
        phoneNumber: '+237600000003',
        role: 'OWNER',
      );

      await tester.pumpWidget(_buildPage(profile: profile));
      await tester.pumpAndSettle();

      expect(find.text('Changer mon mot de passe'), findsOneWidget);
    });

    testWidgets('error state — shows error banner + retry button',
        (tester) async {
      await tester.pumpWidget(
          _buildPage(profile: null, error: Exception('network error')));
      await tester.pumpAndSettle();

      expect(find.text('Impossible de charger le profil'), findsOneWidget);
      expect(find.text('Réessayer'), findsOneWidget);
    });
  });
}
