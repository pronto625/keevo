import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/features/account/domain/model/deletion_pending_status.dart';
import 'package:keevo/features/account/presentation/provider/account_deletion_provider.dart';
import 'package:keevo/features/auth/domain/model/account_profile.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/settings/presentation/page/account_page.dart';
import 'package:keevo_core/state/provider_retry.dart';

/// Builds AccountPage wrapped with ProviderScope + GoRouter.
///
/// [profile] = null means loading (Completer-based, no pending timer).
/// [error] = non-null means error state.
/// [deletionStatus] = non-null means a deletion is pending (banner shown).
Widget _buildPage({
  required AccountProfile? profile,
  Object? error,
  DeletionPendingStatus? deletionStatus,
}) {
  return ProviderScope(
    retry: noProviderRetry,
    overrides: [
      accountProfileProvider.overrideWith((_) async {
        if (error != null) throw error;
        if (profile != null) return profile;
        // Loading state: Completer future never fires — no pending timer.
        return Completer<AccountProfile>().future;
      }),
      deletionPendingStatusProvider.overrideWith((_) async => deletionStatus),
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
        GoRoute(
          path: '/settings/account/delete',
          builder: (_, __) => const Scaffold(
            body: Center(child: Text('DeleteAccountPage')),
          ),
        ),
      ]),
    ),
  );
}

const _owner = AccountProfile(
  userId: 'u2',
  phoneNumber: '+237600000002',
  role: 'OWNER',
);

const _employee = AccountProfile(
  userId: 'u1',
  phoneNumber: '+237600000001',
  role: 'EMPLOYEE',
  firstName: 'Jean',
  lastName: 'Dupont',
  storeId: 's1',
  storeName: 'Boutique Centrale',
);

Future<void> _scrollTo(WidgetTester tester, Finder finder) =>
    tester.scrollUntilVisible(finder, 200,
        scrollable: find.byType(Scrollable).first);

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

  group('AccountPage — Zone dangereuse (Story 14.5 AC7/AC8/AC9)', () {
    testWidgets('OWNER sees the "Zone dangereuse" with the delete tile',
        (tester) async {
      await tester.pumpWidget(_buildPage(profile: _owner));
      await tester.pumpAndSettle();
      await _scrollTo(tester, find.text('Supprimer mon compte'));

      expect(find.text('ZONE DANGEREUSE'), findsOneWidget);
      expect(find.text('Supprimer mon compte'), findsOneWidget);
      expect(find.textContaining('Votre compte est lié à'), findsNothing);
    });

    testWidgets('EMPLOYEE sees the explanation and no delete action',
        (tester) async {
      await tester.pumpWidget(_buildPage(profile: _employee));
      await tester.pumpAndSettle();
      await _scrollTo(tester, find.textContaining('Votre compte est lié à'));

      expect(
        find.text('Votre compte est lié à Boutique Centrale. Pour supprimer '
            'vos données, demandez à votre propriétaire de vous retirer de '
            'l\'équipe.'),
        findsOneWidget,
      );
      expect(find.text('Supprimer mon compte'), findsNothing);
      expect(find.text('ZONE DANGEREUSE'), findsNothing);
    });

    testWidgets('delete tile → warning dialog → "Continuer" opens step 2',
        (tester) async {
      await tester.pumpWidget(_buildPage(profile: _owner));
      await tester.pumpAndSettle();
      await _scrollTo(tester, find.text('Supprimer mon compte'));
      await tester.tap(find.text('Supprimer mon compte'));
      await tester.pumpAndSettle();

      expect(find.text('Supprimer mon compte ?'), findsOneWidget);
      expect(find.text('Toutes vos ventes et clôtures'), findsOneWidget);
      expect(find.text('Vos employés et leurs accès'), findsOneWidget);

      await tester.tap(find.text('Continuer'));
      await tester.pumpAndSettle();

      expect(find.text('DeleteAccountPage'), findsOneWidget);
    });

    testWidgets('warning dialog "Annuler" stays on the profile',
        (tester) async {
      await tester.pumpWidget(_buildPage(profile: _owner));
      await tester.pumpAndSettle();
      await _scrollTo(tester, find.text('Supprimer mon compte'));
      await tester.tap(find.text('Supprimer mon compte'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Annuler'));
      await tester.pumpAndSettle();

      expect(find.text('Supprimer mon compte ?'), findsNothing);
      expect(find.text('DeleteAccountPage'), findsNothing);
      expect(find.text('Mon Compte'), findsOneWidget);
    });

    testWidgets('no banner when nothing is pending', (tester) async {
      await tester.pumpWidget(_buildPage(profile: _owner));
      await tester.pumpAndSettle();

      expect(find.text('Annuler la suppression'), findsNothing);
    });

    testWidgets('the DeletionPendingBanner shows above the profile when pending',
        (tester) async {
      await tester.pumpWidget(_buildPage(
        profile: _owner,
        deletionStatus: DeletionPendingStatus(
            scheduledAt: DateTime.now().add(const Duration(days: 30))),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('Suppression programmée le'), findsOneWidget);
      expect(find.textContaining('30 jours restants'), findsOneWidget);
      expect(find.text('Annuler la suppression'), findsOneWidget);
    });
  });
}
