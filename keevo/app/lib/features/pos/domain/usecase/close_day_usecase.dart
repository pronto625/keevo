import 'package:uuid/uuid.dart';

import '../model/day_closure_model.dart';
import '../repository/day_closure_repository.dart';

/// CloseDayUseCase — Performs the day closure operation.
///
/// Story 4.4 — Clôture Journalière & Historique des Ventes.
class CloseDayUseCase {
  final DayClosureRepository _repository;
  final Uuid _uuid;

  CloseDayUseCase({
    required DayClosureRepository repository,
    Uuid? uuid,
  })  : _repository = repository,
        _uuid = uuid ?? const Uuid();

  /// Execute the day closure.
  ///
  /// 1. Compute today's summary from local sales
  /// 2. Create and save the closure record
  /// 3. Enqueue for sync to backend
  ///
  /// Throws [StateError] if the day has already been closed.
  Future<DayClosureSummary> execute({
    required String storeId,
    required String actorId,
  }) async {
    // Check if already closed today
    final alreadyClosed = await _repository.hasClosureToday(storeId);
    if (alreadyClosed) {
      throw StateError('La journée a déjà été clôturée pour cette boutique');
    }

    // Compute summary from local sales
    final summary = await _repository.computeTodaySummary(storeId, null);

    // Create closure record
    final closure = DayClosure(
      id: _uuid.v4(),
      storeId: storeId,
      actorId: actorId,
      closedAt: DateTime.now(),
      summary: summary,
      isAutomatic: false,
      synced: false,
    );

    // Save locally and enqueue for sync
    await _repository.saveClosureLocally(closure);

    return summary;
  }
}
