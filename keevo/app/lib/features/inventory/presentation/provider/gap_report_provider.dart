import 'dart:developer' as dev;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
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
  GapReportRef ref,
  String sessionId,
) async {
  // Backend-First: try remote first (has all counts correctly)
  final connectivity = ref.read(connectivityServiceProvider);
  if (await connectivity.isOnline()) {
    try {
      final dio = ref.read(dioProvider);
      final response = await dio.get<Map<String, dynamic>>(
        '/api/v1/inventory/sessions/$sessionId/gap-report',
      );
      final data = response.data!['data'] as Map<String, dynamic>;
      return InventoryGapReportModel.fromJson(data);
    } catch (e) {
      dev.log('Remote gap report failed, falling back to local: $e',
          name: 'GapReportProvider');
    }
  }

  // Offline fallback: generate from local Drift tables
  final localDs = ref.read(localGapReportDataSourceProvider);
  return localDs.generate(sessionId);
}

/// Generates emoji-rich WhatsApp text locally.
@riverpod
String gapReportWhatsAppText(
  GapReportWhatsAppTextRef ref,
  InventoryGapReportModel report,
  String actorName,
) {
  return formatWhatsAppReport(report, actorName);
}

/// Generates detailed text for export/download.
@riverpod
String gapReportDetailedText(
  GapReportDetailedTextRef ref,
  InventoryGapReportModel report,
  String actorName,
) {
  return formatDetailedTextReport(report, actorName);
}
