import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../data/local/local_export_reader.dart';
import '../../data/local/local_export_service.dart';

/// Reads the export datasets out of the local Drift database (v1s-18-5).
final localExportReaderProvider = Provider<LocalExportReader>(
    (ref) => LocalExportReader(ref.watch(appDatabaseProvider)));

/// Local row count of every catalogue dataset (« Aucune donnée sur cet
/// appareil » when 0).
final localExportCountsProvider = FutureProvider.autoDispose<Map<String, int>>(
    (ref) => ref.watch(localExportReaderProvider).counts());

/// On-device CSV export (reader → worker-isolate writer → temp file).
final localExportServiceProvider = Provider<LocalExportService>(
    (ref) => LocalExportService(ref.watch(localExportReaderProvider)));
