import 'package:uuid/uuid.dart';

import '../../../../core/sync/sync_service.dart';
import '../../../../core/sync/sync_trigger_dispatcher.dart';
import '../../domain/model/feedback_submission.dart';
import '../../domain/repository/feedback_repository.dart';

/// FeedbackRepositoryImpl — offline-first feedback (Story 14.5, AC15).
///
/// No direct REST call: the entry goes through `sync_queue` as a
/// `SUBMIT_FEEDBACK` operation (handled server-side by
/// `SubmitFeedbackSyncHandler`, same service as `POST /api/v1/feedback`),
/// then a background push is triggered — mirror of
/// `stock_repository_impl.dart`.
class FeedbackRepositoryImpl implements FeedbackRepository {
  static const operation = 'SUBMIT_FEEDBACK';

  final SyncService _syncService;
  final SyncTriggerDispatcher _syncTriggerDispatcher;
  final Uuid _uuid;

  const FeedbackRepositoryImpl({
    required SyncService syncService,
    required SyncTriggerDispatcher syncTriggerDispatcher,
    Uuid uuid = const Uuid(),
  })  : _syncService = syncService,
        _syncTriggerDispatcher = syncTriggerDispatcher,
        _uuid = uuid;

  @override
  Future<String> submit(FeedbackSubmission submission) async {
    final id = _uuid.v4();
    await _syncService.queueOperation(
      operation: operation,
      payload: submission.toPayload(),
      entityId: id,
    );
    // Fire-and-forget: never awaited.
    _syncTriggerDispatcher.triggerPushIfIdle();
    return id;
  }
}
