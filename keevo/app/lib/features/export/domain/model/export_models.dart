import 'dart:io';

/// Key of the audit log dataset — the only one declaring criteria (v1s-18-2).
const kAuditLogDatasetKey = 'audit_log';

/// An exportable dataset (`GET /api/v1/exports/datasets`, v1s-18-1/18-2).
class ExportDataset {
  final String key;
  final String label;
  final bool storeFilter;
  final bool periodFilter;

  /// Dataset-specific criteria keys this dataset accepts.
  final List<String> criteria;

  const ExportDataset({
    required this.key,
    required this.label,
    this.storeFilter = false,
    this.periodFilter = false,
    this.criteria = const [],
  });

  factory ExportDataset.fromJson(Map<String, dynamic> json) => ExportDataset(
        key: json['key'] as String,
        label: (json['label'] as String?) ?? json['key'] as String,
        storeFilter: json['storeFilter'] as bool? ?? false,
        periodFilter: json['periodFilter'] as bool? ?? false,
        criteria: [
          for (final c in (json['criteria'] as List<dynamic>? ?? const []))
            c as String,
        ],
      );
}

/// Export file formats. PDF is not available before v1s-18-3.
enum ExportFormat {
  csv('CSV', 'CSV'),
  xlsx('XLSX', 'Excel'),
  pdf('PDF', 'PDF');

  final String apiValue;
  final String label;

  const ExportFormat(this.apiValue, this.label);

  static ExportFormat? fromApi(String? value) {
    for (final f in values) {
      if (f.apiValue == value) return f;
    }
    return null;
  }
}

/// Server-side status of a background export job.
enum ExportJobStatus { pending, running, ready, failed, expired, unknown }

ExportJobStatus _statusFromApi(String? s) => switch (s) {
      'PENDING' => ExportJobStatus.pending,
      'RUNNING' => ExportJobStatus.running,
      'READY' => ExportJobStatus.ready,
      'FAILED' => ExportJobStatus.failed,
      'EXPIRED' => ExportJobStatus.expired,
      _ => ExportJobStatus.unknown,
    };

DateTime? _instant(Object? v) =>
    v is String ? DateTime.tryParse(v)?.toLocal() : null;

/// A background export job (« Mes exports », `ExportJobDto`).
class ExportJob {
  final String id;
  final ExportJobStatus status;

  /// Raw API format (`CSV` | `XLSX` | `PDF`).
  final String format;
  final List<String> datasets;
  final String? storeId;
  final DateTime? from;
  final DateTime? to;
  final int? rowCount;
  final String? fileName;
  final int? fileSize;
  final String? contentType;
  final String? errorCode;
  final DateTime? createdAt;
  final DateTime? completedAt;
  final DateTime? expiresAt;

  const ExportJob({
    required this.id,
    required this.status,
    required this.format,
    this.datasets = const [],
    this.storeId,
    this.from,
    this.to,
    this.rowCount,
    this.fileName,
    this.fileSize,
    this.contentType,
    this.errorCode,
    this.createdAt,
    this.completedAt,
    this.expiresAt,
  });

  factory ExportJob.fromJson(Map<String, dynamic> json) => ExportJob(
        id: json['id'] as String,
        status: _statusFromApi(json['status'] as String?),
        format: (json['format'] as String?) ?? 'CSV',
        datasets: [
          for (final d in (json['datasets'] as List<dynamic>? ?? const []))
            d as String,
        ],
        storeId: json['storeId'] as String?,
        from: _instant(json['from']),
        to: _instant(json['to']),
        rowCount: (json['rowCount'] as num?)?.toInt(),
        fileName: json['fileName'] as String?,
        fileSize: (json['fileSize'] as num?)?.toInt(),
        contentType: json['contentType'] as String?,
        errorCode: json['errorCode'] as String?,
        createdAt: _instant(json['createdAt']),
        completedAt: _instant(json['completedAt']),
        expiresAt: _instant(json['expiresAt']),
      );

  ExportFormat? get exportFormat => ExportFormat.fromApi(format);

  /// EXPIRED, or READY with an expiry in the past (the hourly purge has not
  /// run yet).
  bool isExpired(DateTime now) =>
      status == ExportJobStatus.expired ||
      (status == ExportJobStatus.ready &&
          expiresAt != null &&
          expiresAt!.isBefore(now));

  /// Status as shown to the user.
  ExportJobStatus effectiveStatus(DateTime now) =>
      isExpired(now) ? ExportJobStatus.expired : status;

  bool get isActive =>
      status == ExportJobStatus.pending || status == ExportJobStatus.running;
}

/// Pre-filled choices of the « Exporter » screen (entry buttons).
class ExportPrefill {
  final Set<String> datasets;
  final String? storeId;
  final DateTime? from;
  final DateTime? to;

  /// Audit log criteria (`userId`, `action`, `entityType`, `entityId`, `q`).
  final Map<String, String> criteria;

  const ExportPrefill({
    this.datasets = const {},
    this.storeId,
    this.from,
    this.to,
    this.criteria = const {},
  });
}

/// Body of `POST /api/v1/exports`.
class ExportRequest {
  final List<String> datasets;
  final ExportFormat format;
  final String? storeId;
  final DateTime? from;
  final DateTime? to;
  final Map<String, String>? criteria;

  const ExportRequest({
    required this.datasets,
    required this.format,
    this.storeId,
    this.from,
    this.to,
    this.criteria,
  });

  Map<String, dynamic> toJson() => {
        'datasets': datasets,
        'format': format.apiValue,
        if (storeId != null) 'storeId': storeId,
        if (from != null) 'from': from!.toUtc().toIso8601String(),
        if (to != null) 'to': to!.toUtc().toIso8601String(),
        if (criteria != null && criteria!.isNotEmpty) 'criteria': criteria,
      };
}

/// Result of a launched export.
sealed class ExportOutcome {
  const ExportOutcome();
}

/// Direct export: the file is in the app temp directory.
class ExportFileReady extends ExportOutcome {
  final File file;
  const ExportFileReady(this.file);
}

/// Background export (Premium): the job is listed in « Mes exports ».
class ExportQueued extends ExportOutcome {
  final ExportJob job;
  const ExportQueued(this.job);
}

/// True when the criteria apply: the selection is exactly the audit log.
bool exportCriteriaApply(Set<String> selection) =>
    selection.length == 1 && selection.first == kAuditLogDatasetKey;

/// Builds the request of the « Exporter » screen.
///
/// - datasets follow the catalogue order (unknown keys appended);
/// - `storeId` / `from` / `to` are dropped when no selected dataset honours
///   them (known catalogue only);
/// - `criteria` are sent only when the selection is exactly `{audit_log}`,
///   restricted to the keys that dataset declares, blank values dropped.
///
/// Throws [ArgumentError] for PDF (not available before v1s-18-3) or an empty
/// selection.
ExportRequest buildExportRequest({
  required Set<String> selection,
  required ExportFormat format,
  String? storeId,
  DateTime? from,
  DateTime? to,
  Map<String, String> criteria = const {},
  List<ExportDataset> datasets = const [],
}) {
  if (format == ExportFormat.pdf) {
    throw ArgumentError.value(format, 'format', 'PDF is not available yet');
  }
  if (selection.isEmpty) {
    throw ArgumentError.value(selection, 'selection', 'empty selection');
  }
  final byKey = {for (final d in datasets) d.key: d};
  final ordered = [
    for (final d in datasets)
      if (selection.contains(d.key)) d.key,
    for (final k in selection)
      if (!byKey.containsKey(k)) k,
  ];
  final known = [
    for (final k in ordered)
      if (byKey[k] != null) byKey[k]!,
  ];
  final allKnown = known.length == ordered.length;
  final storeApplies = !allKnown || known.any((d) => d.storeFilter);
  final periodApplies = !allKnown || known.any((d) => d.periodFilter);

  Map<String, String>? sentCriteria;
  if (exportCriteriaApply(selection)) {
    final declared = byKey[kAuditLogDatasetKey]?.criteria ?? const [];
    final kept = <String, String>{
      for (final e in criteria.entries)
        if (declared.contains(e.key) && e.value.trim().isNotEmpty)
          e.key: e.value.trim(),
    };
    if (kept.isNotEmpty) sentCriteria = kept;
  }

  return ExportRequest(
    datasets: ordered,
    format: format,
    storeId: storeApplies ? storeId : null,
    from: periodApplies ? from : null,
    to: periodApplies ? to : null,
    criteria: sentCriteria,
  );
}
