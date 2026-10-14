import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
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

/// Renews the session after a deletion request or cancellation (A50): the
/// access token's `tenantStatus` claim is frozen at issuance and drives the
/// read-only mode (server write block, local guard), so it must carry the
/// tenant's live status right away. Best effort — offline, the claim is
/// renewed with the next token.
Future<void> renewSessionAfterAccountChange(WidgetRef ref) async {
  try {
    await ref.read(sessionRefresherProvider).refresh();
  } catch (_) {
    // Kept signed in either way; the next renewal brings the status.
  }
  ref.invalidate(tenantStatusClaimProvider);
}

/// Null when no deletion is scheduled. Invalidate after a request or a
/// cancellation so [DeletionPendingBanner] updates immediately.
final deletionPendingStatusProvider =
    FutureProvider<DeletionPendingStatus?>((ref) {
  return ref.watch(accountDeletionRepositoryProvider).currentStatus();
});
