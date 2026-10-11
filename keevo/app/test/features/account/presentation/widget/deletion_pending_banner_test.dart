import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/account/domain/model/deletion_pending_status.dart';
import 'package:keevo/features/account/domain/repository/account_deletion_repository.dart';
import 'package:keevo/features/account/presentation/provider/account_deletion_provider.dart';
import 'package:keevo/features/account/presentation/widget/deletion_pending_banner.dart';

class _MockRepo extends Mock implements AccountDeletionRepository {}

Widget _build(AccountDeletionRepository repo) => ProviderScope(
      overrides: [accountDeletionRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: Scaffold(body: DeletionPendingBanner())),
    );

void main() {
  late _MockRepo repo;

  setUp(() {
    repo = _MockRepo();
    when(() => repo.cancelDeletion()).thenAnswer((_) async {});
  });

  group('DeletionPendingBanner (Story 14.5 — AC8)', () {
    testWidgets('nothing rendered when no deletion is pending', (tester) async {
      when(() => repo.currentStatus()).thenAnswer((_) async => null);

      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();

      expect(find.textContaining('Suppression'), findsNothing);
      expect(find.text('Annuler la suppression'), findsNothing);
    });

    testWidgets('shows the date, the days left and the cancel action',
        (tester) async {
      final scheduledAt = DateTime.now().add(const Duration(days: 12));
      when(() => repo.currentStatus()).thenAnswer(
          (_) async => DeletionPendingStatus(scheduledAt: scheduledAt));

      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();

      final dd = scheduledAt.day.toString().padLeft(2, '0');
      final mm = scheduledAt.month.toString().padLeft(2, '0');
      expect(
        find.textContaining(
            'Suppression programmée le $dd/$mm/${scheduledAt.year} — 12 jours restants'),
        findsOneWidget,
      );
      expect(find.text('Annuler la suppression'), findsOneWidget);
    });

    testWidgets('pending from the JWT claim only (no date) still shows',
        (tester) async {
      when(() => repo.currentStatus())
          .thenAnswer((_) async => const DeletionPendingStatus());

      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();

      expect(find.text('Suppression de votre compte programmée.'),
          findsOneWidget);
      expect(find.text('Annuler la suppression'), findsOneWidget);
    });

    testWidgets('cancel calls the repository and hides the banner',
        (tester) async {
      var pending = true;
      when(() => repo.currentStatus()).thenAnswer((_) async => pending
          ? DeletionPendingStatus(
              scheduledAt: DateTime.now().add(const Duration(days: 30)))
          : null);
      when(() => repo.cancelDeletion()).thenAnswer((_) async {
        pending = false;
      });

      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Annuler la suppression'));
      await tester.pumpAndSettle();

      verify(() => repo.cancelDeletion()).called(1);
      expect(find.text('Annuler la suppression'), findsNothing);
      expect(
        find.text('Suppression annulée — votre compte est de nouveau actif.'),
        findsOneWidget,
      );
    });

    testWidgets('a failed cancel keeps the banner and reports the error',
        (tester) async {
      when(() => repo.currentStatus()).thenAnswer((_) async =>
          DeletionPendingStatus(
              scheduledAt: DateTime.now().add(const Duration(days: 3))));
      when(() => repo.cancelDeletion())
          .thenThrow(Exception('Serveur indisponible'));

      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Annuler la suppression'));
      await tester.pumpAndSettle();

      expect(find.text('Annuler la suppression'), findsOneWidget);
      expect(find.text('Serveur indisponible'), findsOneWidget);
    });
  });
}
