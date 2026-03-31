import '../model/report_history_model.dart';

/// Domain interface for retrieving report history.
/// Story 7.2 — Rapport End-of-Day.
abstract class ReportHistoryRepository {
  /// Fetch paginated list of reports for the current tenant.
  /// [type]: optional filter ('DAILY' | 'DAILY_COMBINED'). Null = all types.
  Future<List<ReportHistoryModel>> getReportHistory({
    required int page,
    required int size,
    String? type,
  });

  /// Fetch a single report by ID.
  Future<ReportHistoryModel?> getReportById(String reportId);

  /// Trigger a WhatsApp resend for a report.
  /// Throws [Exception] if report is already sent or not found.
  Future<void> resendReport(String reportId);
}
