import 'dart:io';

import 'package:drift/drift.dart' show OrderingTerm;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../../settings/presentation/provider/subscription_info_provider.dart';
import '../../data/remote_export_datasource.dart';
import '../../domain/exception/export_exception.dart';
import '../../domain/model/export_models.dart';

/// HTTP adapter of the export API.
final exportDataSourceProvider = Provider<RemoteExportDataSource>(
    (ref) => RemoteExportDataSource(dio: ref.watch(dioProvider)));

/// App temp directory — exported files land in `<temp>/exports/`.
final exportTempDirProvider =
    FutureProvider<Directory>((ref) => getTemporaryDirectory());

/// Hands a downloaded file to the system share sheet.
typedef ExportFileSharer = Future<void> Function(File file);

final exportFileSharerProvider = Provider<ExportFileSharer>((ref) {
  return (file) async {
    final name =
        file.uri.pathSegments.isEmpty ? 'export' : file.uri.pathSegments.last;
    await Share.shareXFiles([XFile(file.path)], subject: name);
  };
});

/// Exportable datasets (labels and applicable filters come from the server).
final exportDatasetsProvider =
    FutureProvider.autoDispose<List<ExportDataset>>((ref) {
  return ref.watch(exportDataSourceProvider).getDatasets();
});

/// Whether the device is online (checked once per screen).
final exportOnlineProvider = FutureProvider.autoDispose<bool>((ref) {
  return ref.watch(connectivityServiceProvider).isOnline();
});

/// « Mes exports » — background jobs, newest first. No offline cache.
final exportJobsProvider =
    FutureProvider.autoDispose<List<ExportJob>>((ref) async {
  // Watched before the await: Riverpod 3 throws on a ref used after its
  // provider was disposed / rebuilt meanwhile.
  final dataSource = ref.watch(exportDataSourceProvider);
  final online = await ref.watch(exportOnlineProvider.future);
  if (!online) throw ExportException.offline;
  return dataSource.listJobs();
});

/// Premium status for the format badges: `true` Premium, `false` FREE,
/// `null` unknown (subscription unavailable — the server decides on launch).
final exportIsPremiumProvider = FutureProvider.autoDispose<bool?>((ref) async {
  try {
    final info = await ref.watch(subscriptionInfoProvider.future);
    return info.planType != 'FREE' && info.status == 'ACTIVE';
  } catch (_) {
    return null;
  }
});

/// A store choice of the « Boutique » filter.
class ExportStoreOption {
  final String id;
  final String label;

  const ExportStoreOption(this.id, this.label);
}

/// Stores known on this device.
final exportStoreOptionsProvider =
    FutureProvider.autoDispose<List<ExportStoreOption>>((ref) async {
  final db = ref.watch(appDatabaseProvider);
  final stores = await (db.select(db.stores)
        ..orderBy([(t) => OrderingTerm.asc(t.name)]))
      .get();
  return [for (final s in stores) ExportStoreOption(s.id, s.name)];
});

/// Auto-refresh interval of « Mes exports » while a job is pending/running.
final exportJobsRefreshIntervalProvider =
    Provider<Duration>((ref) => const Duration(seconds: 10));
