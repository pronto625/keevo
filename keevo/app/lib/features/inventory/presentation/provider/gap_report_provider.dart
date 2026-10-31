import 'dart:developer' as dev;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/local_gap_report_datasource.dart';
import '../../domain/model/inventory_gap_report_model.dart';
import '../../domain/service/inventory_report_text_formatter.dart';

part 'gap_report_provider.g.dart';

// ── Infrastructure ──────────────────────────────────────────────────────────

final localGapReportDataSourceProvider =
    Provider<LocalGapReportDataSource>((ref) {
  final db = ref.watch(appDatabaseProvider);
  return LocalGapReportDataSource(db);
});

// ── Reactive providers ──────────────────────────────────────────────────────

/// Generates gap report — Backend-First pattern.
/// Online: fetch from backend (has ALL counts with correct theoretical values).
/// Offline: fall back to local Drift generation.
@riverpod
Future<InventoryGapReportModel> gapReport(
  Ref ref,
  String sessionId,
) async {
  // Read before the first await: Riverpod 3 throws on a ref used after this
  // auto-dispose provider was disposed meanwhile.
  final connectivity = ref.read(connectivityServiceProvider);
  final dio = ref.read(dioProvider);
  final localDs = ref.read(localGapReportDataSourceProvider);
  // Backend-First: try remote first (has all counts correctly)
  if (await connectivity.isOnline()) {
    try {
      final response = await dio.get<Map<String, dynamic>>(
        '/api/v1/inventory/sessions/$sessionId/gap-report',
      );
      final data = response.data!['data'] as Map<String, dynamic>;
      final report = InventoryGapReportModel.fromJson(data);
      return localDs.withLocalCategoryNames(report);
    } catch (e) {
      dev.log('Remote gap report failed, falling back to local: $e',
          name: 'GapReportProvider');
    }
  }

  // Offline fallback: generate from local Drift tables
  return localDs.generate(sessionId);
}

/// Generates emoji-rich WhatsApp text locally.
@riverpod
String gapReportWhatsAppText(
  Ref ref,
  InventoryGapReportModel report,
  String actorName,
) {
  return formatWhatsAppReport(report, actorName);
}

/// Generates detailed text for export/download.
@riverpod
String gapReportDetailedText(
  Ref ref,
  InventoryGapReportModel report,
  String actorName,
) {
  return formatDetailedTextReport(report, actorName);
}
