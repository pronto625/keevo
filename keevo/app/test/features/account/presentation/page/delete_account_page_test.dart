import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/auth/session_refresher.dart';
import 'package:keevo/features/account/domain/model/deletion_pending_status.dart';
import 'package:keevo/features/account/domain/repository/account_deletion_repository.dart';
import 'package:keevo/features/account/presentation/page/delete_account_page.dart';
import 'package:keevo/features/account/presentation/provider/account_deletion_provider.dart';
import 'package:keevo/features/auth/domain/model/account_profile.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/settings/presentation/provider/account_status_provider.dart';

class _MockRepo extends Mock implements AccountDeletionRepository {}

class _MockRefresher extends Mock implements SessionRefresher {}

late _MockRefresher _refresher;

const _owner = AccountProfile(
  userId: 'u1',
  phoneNumber: '+237600000001',
  role: 'OWNER',
);

const _employee = AccountProfile(
  userId: 'u2',
  phoneNumber: '+237600000002',
  role: 'EMPLOYEE',
  firstName: 'Loïc',
  storeId: 's1',
  storeName: 'Boutique Centrale',
);

/// The page is pushed on top of a home route so `context.pop()` has
/// somewhere to go.
Widget _build(AccountDeletionRepository repo, AccountProfile profile) {
  return ProviderScope(
    overrides: [
      accountDeletionRepositoryProvider.overrideWithValue(repo),
      accountProfileProvider.overrideWith((_) async => profile),
      sessionRefresherProvider.overrideWithValue(_refresher),
      tenantStatusClaimProvider.overrideWith((_) async => 'ACTIVE'),
    ],
    child: MaterialApp.router(
      routerConfig: GoRouter(
        initialLocation: '/account/delete',
        routes: [
          GoRoute(
            path: '/account',
            builder: (_, __) =>
                const Scaffold(body: Center(child: Text('AccountPage'))),
            routes: [
              GoRoute(
                path: 'delete',
                builder: (_, __) => const DeleteAccountPage(),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}

FilledButton _confirmButton(WidgetTester tester) => tester.widget<FilledButton>(
      find.ancestor(
        of: find.text('Confirmer la suppression'),
        matching: find.byType(FilledButton),
      ),
    );

void main() {
  late _MockRepo repo;

  setUp(() {
    repo = _MockRepo();
    _refresher = _MockRefresher();
    when(() => _refresher.refresh())
        .thenAnswer((_) async => RefreshOutcome.renewed);
    when(() => repo.currentStatus()).thenAnswer((_) async => null);
    when(() => repo.requestDeletion()).thenAnswer((_) async =>
        DeletionPendingStatus(scheduledAt: DateTime(2026, 11, 10, 9)));
  });

  group('DeleteAccountPage (Story 14.5 — AC7)', () {
    testWidgets('"Confirmer" stays disabled until the typed number matches',
        (tester) async {
      await tester.pumpWidget(_build(repo, _owner));
      await tester.pumpAndSettle();

      expect(find.text('+237600000001'), findsOneWidget);
      expect(_confirmButton(tester).onPressed, isNull);

      await tester.enterText(find.byType(TextField), '+237600000009');
      await tester.pump();
      expect(_confirmButton(tester).onPressed, isNull);

      // Spaces are ignored: the user may type the number with groups.
      await tester.enterText(find.byType(TextField), '+237 600 000 001');
      await tester.pump();
      expect(_confirmButton(tester).onPressed, isNotNull);

      verifyNever(() => repo.requestDeletion());
    });

    testWidgets('confirming calls the API, shows the SnackBar and goes back',
        (tester) async {
      await tester.pumpWidget(_build(repo, _owner));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '+237600000001');
      await tester.pump();
      await tester.ensureVisible(find.text('Confirmer la suppression'));
      await tester.tap(find.text('Confirmer la suppression'));
      await tester.pumpAndSettle();

      verify(() => repo.requestDeletion()).called(1);
      // A50: the token is renewed so its tenantStatus says DELETION_PENDING
      // (read-only) at once, not after the 60-day access token expires.
      verify(() => _refresher.refresh()).called(1);
      expect(
        find.text('Votre demande de suppression a été enregistrée. Vos '
            'données seront supprimées le 10/11/2026.'),
        findsOneWidget,
      );
      expect(find.text('AccountPage'), findsOneWidget);
    });

    testWidgets('an API error is shown inline and the page stays',
        (tester) async {
      when(() => repo.requestDeletion())
          .thenThrow(Exception('Demande déjà enregistrée'));

      await tester.pumpWidget(_build(repo, _owner));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '+237600000001');
      await tester.pump();
      await tester.ensureVisible(find.text('Confirmer la suppression'));
      await tester.tap(find.text('Confirmer la suppression'));
      await tester.pumpAndSettle();

      expect(find.text('Demande déjà enregistrée'), findsOneWidget);
      expect(find.text('AccountPage'), findsNothing);
      expect(_confirmButton(tester).onPressed, isNotNull);
      verifyNever(() => _refresher.refresh());
    });

    testWidgets('"Annuler" goes back without calling the API', (tester) async {
      await tester.pumpWidget(_build(repo, _owner));
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('Annuler'));
      await tester.tap(find.text('Annuler'));
      await tester.pumpAndSettle();

      expect(find.text('AccountPage'), findsOneWidget);
      verifyNever(() => repo.requestDeletion());
    });

    testWidgets('EMPLOYEE sees the explanation and no confirm button (AC9)',
        (tester) async {
      await tester.pumpWidget(_build(repo, _employee));
      await tester.pumpAndSettle();

      expect(
        find.text('Votre compte est lié à Boutique Centrale. Pour supprimer '
            'vos données, demandez à votre propriétaire de vous retirer de '
            'l\'équipe.'),
        findsOneWidget,
      );
      expect(find.text('Confirmer la suppression'), findsNothing);
      expect(find.byType(TextField), findsNothing);
    });
  });
}
