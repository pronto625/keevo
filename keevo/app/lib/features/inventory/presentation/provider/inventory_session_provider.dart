import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_inventory_session_datasource.dart';
import '../../data/datasource/remote_inventory_session_datasource.dart';
import '../../data/repository/inventory_session_repository_impl.dart';
import '../../domain/model/inventory_session_model.dart';
import '../../domain/repository/inventory_session_repository.dart';
import '../../domain/usecase/create_inventory_session_usecase.dart';
import '../../domain/usecase/cancel_inventory_session_usecase.dart';

part 'inventory_session_provider.g.dart';

// ── Infrastructure ──────────────────────────────────────────────────────────

final localInventorySessionDsProvider =
    Provider<LocalInventorySessionDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalInventorySessionDataSource(db);
});

final remoteInventorySessionDsProvider =
    Provider<RemoteInventorySessionDataSource>((ref) {
  final dio = ref.watch(dioProvider);
  return RemoteInventorySessionDataSource(dio: dio);
});

final inventorySessionRepositoryProvider =
    Provider<InventorySessionRepository>((ref) {
  return InventorySessionRepositoryImpl(
    local: ref.watch(localInventorySessionDsProvider),
    remote: ref.watch(remoteInventorySessionDsProvider),
    connectivity: ref.watch(connectivityServiceProvider),
    syncService: ref.watch(syncServiceProvider),
  );
});

final createInventorySessionUseCaseProvider =
    Provider<CreateInventorySessionUseCase>((ref) {
  return CreateInventorySessionUseCase(
    ref.watch(inventorySessionRepositoryProvider),
  );
});

final cancelInventorySessionUseCaseProvider =
    Provider<CancelInventorySessionUseCase>((ref) {
  return CancelInventorySessionUseCase(
    ref.watch(inventorySessionRepositoryProvider),
  );
});

// ── Reactive providers ──────────────────────────────────────────────────────

@riverpod
Future<InventorySessionModel?> activeSession(
  ActiveSessionRef ref,
  String storeId,
) async {
  return ref
      .watch(inventorySessionRepositoryProvider)
      .getActiveByStoreId(storeId);
}

@riverpod
Future<List<InventorySessionModel>> sessionHistory(
  SessionHistoryRef ref, {
  int page = 0,
  int size = 20,
}) async {
  return ref
      .watch(inventorySessionRepositoryProvider)
      .getHistory(page: page, size: size);
}

// ── Action notifiers ────────────────────────────────────────────────────────

@riverpod
class CreateSessionNotifier extends _$CreateSessionNotifier {
  @override
  FutureOr<InventorySessionModel?> build() => null;

  Future<InventorySessionModel> create({
    required String storeId,
    required String scope,
    List<String>? categoryIds,
  }) async {
    state = const AsyncLoading();
    final session = await ref
        .read(createInventorySessionUseCaseProvider)
        .execute(storeId: storeId, scope: scope, categoryIds: categoryIds);
    state = AsyncData(session);
    // Invalidate active session and history
    ref.invalidate(activeSessionProvider);
    ref.invalidate(sessionHistoryProvider);
    return session;
  }
}

@riverpod
class CancelSessionNotifier extends _$CancelSessionNotifier {
  @override
  FutureOr<void> build() => null;

  Future<void> cancel(String sessionId) async {
    state = const AsyncLoading();
    try {
      await ref.read(cancelInventorySessionUseCaseProvider).execute(sessionId);
    } catch (e, st) {
      state = AsyncError(e, st);
      return;
    }
    state = const AsyncData(null);
    Future.microtask(() {
      ref.invalidate(activeSessionProvider);
      ref.invalidate(sessionHistoryProvider);
    });
  }
}
