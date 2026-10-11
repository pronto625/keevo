import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/remote_account_deletion_datasource.dart';
import '../../data/repository/account_deletion_repository_impl.dart';
import '../../domain/model/deletion_pending_status.dart';
import '../../domain/repository/account_deletion_repository.dart';

/// Providers for FR91 self-service account deletion (Story 14.5).
///
/// Deliberately independent of `account_status_provider.dart`
/// (`SuspensionBanner`, trial/suspension — Story 14.9).

final remoteAccountDeletionDataSourceProvider =
    Provider<RemoteAccountDeletionDataSource>((ref) {
  return RemoteAccountDeletionDataSource(dio: ref.watch(dioProvider));
});

final accountDeletionRepositoryProvider =
    Provider<AccountDeletionRepository>((ref) {
  return AccountDeletionRepositoryImpl(
    remote: ref.watch(remoteAccountDeletionDataSourceProvider),
    prefs: ref.watch(sharedPreferencesProvider),
    tokenStorage: ref.watch(tokenStorageProvider),
  );
});

/// Null when no deletion is scheduled. Invalidate after a request or a
/// cancellation so [DeletionPendingBanner] updates immediately.
final deletionPendingStatusProvider =
    FutureProvider<DeletionPendingStatus?>((ref) {
  return ref.watch(accountDeletionRepositoryProvider).currentStatus();
});
