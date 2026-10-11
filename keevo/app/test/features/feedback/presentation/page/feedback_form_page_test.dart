import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/features/feedback/domain/model/feedback_submission.dart';
import 'package:keevo/features/feedback/domain/model/feedback_type.dart';
import 'package:keevo/features/feedback/domain/repository/feedback_repository.dart';
import 'package:keevo/features/feedback/presentation/page/feedback_form_page.dart';
import 'package:keevo/features/feedback/presentation/provider/feedback_provider.dart';

class _MockRepo extends Mock implements FeedbackRepository {}

class _FakeSubmission extends Fake implements FeedbackSubmission {}

Widget _build(FeedbackRepository repo, {String? screenContext = '/settings'}) {
  return ProviderScope(
    overrides: [
      feedbackRepositoryProvider.overrideWithValue(repo),
      appVersionProvider.overrideWithValue('1.1.13'),
    ],
    child: MaterialApp(home: FeedbackFormPage(screenContext: screenContext)),
  );
}

Future<void> _typeDescription(WidgetTester tester, String text) async {
  await tester.enterText(find.byType(TextFormField), text);
  await tester.pump();
}

Future<void> _tapSend(WidgetTester tester) async {
  await tester.ensureVisible(find.text('Envoyer'));
  await tester.tap(find.text('Envoyer'));
  await tester.pumpAndSettle();
}

FeedbackSubmission _captured(_MockRepo repo) =>
    verify(() => repo.submit(captureAny())).captured.single
        as FeedbackSubmission;

void main() {
  late _MockRepo repo;

  setUpAll(() => registerFallbackValue(_FakeSubmission()));

  setUp(() {
    repo = _MockRepo();
    when(() => repo.submit(any())).thenAnswer((_) async => 'id-1');
  });

  group('FeedbackFormPage (Story 14.5 — AC14/AC15)', () {
    testWidgets('shows the three types, the live counter and the context',
        (tester) async {
      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();

      expect(find.text('Suggestion'), findsOneWidget);
      expect(find.text('Signaler un problème'), findsOneWidget);
      expect(find.text("J'adore Keevo"), findsOneWidget);
      expect(find.text('0 / 500'), findsOneWidget);
      expect(find.textContaining('Écran : /settings'), findsOneWidget);
      expect(find.textContaining('Version : 1.1.13'), findsOneWidget);

      await _typeDescription(tester, 'Bonjour');
      expect(find.text('7 / 500'), findsOneWidget);
    });

    testWidgets('a description under 10 characters is refused',
        (tester) async {
      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();

      await _typeDescription(tester, 'Court');
      await _tapSend(tester);

      expect(find.textContaining('au moins 10 caractères'), findsOneWidget);
      verifyNever(() => repo.submit(any()));
    });

    testWidgets('a valid submission is queued with its context, '
        'a SnackBar confirms and the form resets', (tester) async {
      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();

      await _typeDescription(
          tester, '  Le total de la vente ne se met pas à jour.  ');
      await _tapSend(tester);

      final submission = _captured(repo);
      expect(submission.type, FeedbackType.bug);
      expect(submission.description,
          'Le total de la vente ne se met pas à jour.');
      expect(submission.screenContext, '/settings');
      expect(submission.appVersion, '1.1.13');
      expect(submission.platform, isNotEmpty);
      expect(submission.screenshotBase64, isNull);

      expect(
        find.text('Merci pour votre retour ! Nous l\'examinerons rapidement.'),
        findsOneWidget,
      );
      // Reset: description cleared, counter back to zero.
      expect(find.text('0 / 500'), findsOneWidget);
      expect(find.text('Le total de la vente ne se met pas à jour.'),
          findsNothing);
    });

    testWidgets('the selected type is sent', (tester) async {
      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Suggestion'));
      await tester.pump();
      await _typeDescription(tester, 'Un raccourci pour la clôture du soir');
      await _tapSend(tester);

      expect(_captured(repo).type, FeedbackType.suggestion);
    });

    testWidgets('a queue failure is shown inline and the text is kept',
        (tester) async {
      when(() => repo.submit(any())).thenThrow(Exception('Base indisponible'));

      await tester.pumpWidget(_build(repo));
      await tester.pumpAndSettle();
      await _typeDescription(tester, 'Impossible de valider une vente');
      await _tapSend(tester);

      expect(find.text('Base indisponible'), findsOneWidget);
      expect(find.text('Impossible de valider une vente'), findsOneWidget);
      expect(find.textContaining('Merci pour votre retour'), findsNothing);
    });

    testWidgets('without a `from` path no screen context is sent',
        (tester) async {
      await tester.pumpWidget(_build(repo, screenContext: null));
      await tester.pumpAndSettle();

      await _typeDescription(tester, 'Juste un petit mot pour dire merci');
      await _tapSend(tester);

      expect(_captured(repo).screenContext, isNull);
    });
  });
}
