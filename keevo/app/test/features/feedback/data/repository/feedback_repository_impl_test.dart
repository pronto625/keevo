import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:keevo/core/sync/sync_service.dart';
import 'package:keevo/core/sync/sync_trigger_dispatcher.dart';
import 'package:keevo/features/feedback/data/repository/feedback_repository_impl.dart';
import 'package:keevo/features/feedback/domain/model/feedback_submission.dart';
import 'package:keevo/features/feedback/domain/model/feedback_type.dart';

class _MockSyncService extends Mock implements SyncService {}

class _MockDispatcher extends Mock implements SyncTriggerDispatcher {}

void main() {
  late _MockSyncService sync;
  late _MockDispatcher dispatcher;
  late FeedbackRepositoryImpl repo;

  setUp(() {
    sync = _MockSyncService();
    dispatcher = _MockDispatcher();
    when(() => sync.queueOperation(
          operation: any(named: 'operation'),
          payload: any(named: 'payload'),
          entityId: any(named: 'entityId'),
        )).thenAnswer((_) async {});
    when(() => dispatcher.triggerPushIfIdle()).thenReturn(null);
    repo = FeedbackRepositoryImpl(
      syncService: sync,
      syncTriggerDispatcher: dispatcher,
    );
  });

  group('FeedbackRepositoryImpl (Story 14.5 — AC15)', () {
    test('queues a SUBMIT_FEEDBACK operation keyed by a fresh UUID, then pushes',
        () async {
      const submission = FeedbackSubmission(
        type: FeedbackType.bug,
        description: 'Le bouton de validation ne fonctionne pas',
        appVersion: '1.1.13',
        platform: 'ANDROID',
        screenContext: '/pos',
        screenshotBase64: 'AAAA',
      );

      final id = await repo.submit(submission);

      final captured = verify(() => sync.queueOperation(
            operation: captureAny(named: 'operation'),
            payload: captureAny(named: 'payload'),
            entityId: captureAny(named: 'entityId'),
          )).captured;
      expect(captured[0], 'SUBMIT_FEEDBACK');
      expect(captured[1], {
        'type': 'BUG',
        'description': 'Le bouton de validation ne fonctionne pas',
        'appVersion': '1.1.13',
        'platform': 'ANDROID',
        'screenContext': '/pos',
        'screenshotBase64': 'AAAA',
      });
      expect(captured[2], id);
      expect(id, matches(RegExp(r'^[0-9a-f-]{36}$')));
      verify(() => dispatcher.triggerPushIfIdle()).called(1);
    });

    test('optional fields are left out of the payload', () async {
      await repo.submit(const FeedbackSubmission(
        type: FeedbackType.suggestion,
        description: 'Ajouter un mode sombre automatique',
      ));

      final payload = verify(() => sync.queueOperation(
            operation: any(named: 'operation'),
            payload: captureAny(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).captured.single as Map<String, dynamic>;
      expect(payload, {
        'type': 'SUGGESTION',
        'description': 'Ajouter un mode sombre automatique',
      });
    });

    test('two submissions get two different ids', () async {
      const s = FeedbackSubmission(
          type: FeedbackType.praise, description: 'Super application !');
      expect(await repo.submit(s), isNot(await repo.submit(s)));
    });

    test('a queue failure propagates and no push is triggered', () async {
      when(() => sync.queueOperation(
            operation: any(named: 'operation'),
            payload: any(named: 'payload'),
            entityId: any(named: 'entityId'),
          )).thenThrow(Exception('db closed'));

      await expectLater(
        repo.submit(const FeedbackSubmission(
            type: FeedbackType.bug, description: 'Écran figé au paiement')),
        throwsException,
      );
      verifyNever(() => dispatcher.triggerPushIfIdle());
    });
  });
}
