import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';

import '../domain/exception/export_exception.dart';
import '../domain/model/export_models.dart';

/// RemoteExportDataSource — HTTP adapter to `/api/v1/exports` (v1s-18-4).
///
/// Files (direct exports, READY job files) are streamed chunk by chunk to
/// `<dir>/exports/<file name>` — never held in memory. Error bodies of stream
/// responses are decoded (bytes → UTF-8 → JSON) to read `domainCode`.
class RemoteExportDataSource {
  static const _base = '/api/v1/exports';

  /// A direct export may take a while between chunks on a slow network.
  static const _streamTimeout = Duration(minutes: 5);

  /// Error bodies larger than this are not read (not a JSON envelope).
  static const _maxErrorBody = 64 * 1024;

  final Dio _dio;

  RemoteExportDataSource({required Dio dio}) : _dio = dio;

  /// `GET /api/v1/exports/datasets`.
  Future<List<ExportDataset>> getDatasets() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>('$_base/datasets');
      final list = response.data!['data'] as List<dynamic>;
      return [
        for (final d in list) ExportDataset.fromJson(d as Map<String, dynamic>)
      ];
    } on DioException catch (e) {
      throw _mapError(e, e.response?.data);
    }
  }

  /// `GET /api/v1/exports` — newest first.
  Future<List<ExportJob>> listJobs() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(_base);
      final list = response.data!['data'] as List<dynamic>;
      final jobs = [
        for (final j in list) ExportJob.fromJson(j as Map<String, dynamic>)
      ];
      final epoch = DateTime.fromMillisecondsSinceEpoch(0);
      jobs.sort(
          (a, b) => (b.createdAt ?? epoch).compareTo(a.createdAt ?? epoch));
      return jobs;
    } on DioException catch (e) {
      throw _mapError(e, e.response?.data);
    }
  }

  /// `GET /api/v1/exports/{id}`.
  Future<ExportJob> getJob(String id) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>('$_base/$id');
      return ExportJob.fromJson(response.data!['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      throw _mapError(e, e.response?.data);
    }
  }

  /// `POST /api/v1/exports`: 200 → the file streamed into [dir]/exports,
  /// 202 → the queued background job.
  Future<ExportOutcome> requestExport(
    ExportRequest request, {
    required Directory dir,
  }) async {
    final Response<ResponseBody> response;
    try {
      response = await _dio.post<ResponseBody>(
        _base,
        data: request.toJson(),
        options: Options(
          responseType: ResponseType.stream,
          receiveTimeout: _streamTimeout,
        ),
      );
    } on DioException catch (e) {
      throw await _mapStreamError(e);
    }
    final body = response.data!;
    if (response.statusCode == 202) {
      final json = await _readJson(body.stream);
      final data = json is Map ? json['data'] : null;
      if (data is! Map<String, dynamic>) throw ExportException.unexpected;
      return ExportQueued(ExportJob.fromJson(data));
    }
    final file = await _writeFile(body, response.headers, dir, null);
    return ExportFileReady(file);
  }

  /// `GET /api/v1/exports/{id}/file` streamed into [dir]/exports.
  Future<File> downloadJobFile(ExportJob job, {required Directory dir}) async {
    final Response<ResponseBody> response;
    try {
      response = await _dio.get<ResponseBody>(
        '$_base/${job.id}/file',
        options: Options(
          responseType: ResponseType.stream,
          receiveTimeout: _streamTimeout,
        ),
      );
    } on DioException catch (e) {
      throw await _mapStreamError(e);
    }
    return _writeFile(response.data!, response.headers, dir, job.fileName);
  }

  // ── File writing ───────────────────────────────────────────────────────────

  Future<File> _writeFile(
    ResponseBody body,
    Headers headers,
    Directory dir,
    String? fallbackName,
  ) async {
    final contentType = headers.value(Headers.contentTypeHeader);
    final name = exportFileName(
      headers.value('content-disposition'),
      contentType,
      fallbackName: fallbackName,
    );
    final sep = Platform.pathSeparator;
    final exportsDir = Directory('${dir.path}${sep}exports');
    // Own subfolder per download: same-named files never overwrite each
    // other, and the file name stays readable in the share sheet.
    final target = Directory(
        '${exportsDir.path}$sep${DateTime.now().microsecondsSinceEpoch}');
    final file = File('${target.path}$sep$name');
    IOSink? sink;
    try {
      await target.create(recursive: true);
      await _purgeOld(exportsDir, keep: target.path);
      sink = file.openWrite();
      await sink.addStream(body.stream);
      await sink.flush();
      await sink.close();
      return file;
    } catch (e) {
      try {
        await sink?.close();
      } catch (_) {}
      try {
        if (await target.exists()) await target.delete(recursive: true);
      } catch (_) {}
      if (e is DioException) throw await _mapStreamError(e);
      if (e is FileSystemException) throw ExportException.storage;
      throw ExportException.network;
    }
  }

  /// Best effort: removes exports older than 24 h (files are sensitive and
  /// the server keeps them 24 h too). Errors are ignored.
  static Future<void> _purgeOld(Directory exportsDir,
      {required String keep}) async {
    final limit = DateTime.now().subtract(const Duration(hours: 24));
    try {
      await for (final entry in exportsDir.list(followLinks: false)) {
        if (entry.path == keep) continue;
        try {
          final modified = (await entry.stat()).modified;
          if (modified.isBefore(limit)) await entry.delete(recursive: true);
        } catch (_) {}
      }
    } catch (_) {}
  }

  // ── Error decoding ─────────────────────────────────────────────────────────

  Future<ExportException> _mapStreamError(DioException e) async {
    final data = e.response?.data;
    Object? body = data;
    if (data is ResponseBody) {
      try {
        body = await _readJson(data.stream);
      } catch (_) {
        body = null;
      }
    }
    return _mapError(e, body);
  }

  ExportException _mapError(DioException e, Object? body) {
    final status = e.response?.statusCode;
    if (body is String) {
      try {
        body = jsonDecode(body);
      } catch (_) {
        body = null;
      }
    }
    return ExportException.fromResponse(status, body);
  }

  static Future<Object?> _readJson(Stream<List<int>> stream) async {
    final bytes = <int>[];
    await for (final chunk in stream) {
      bytes.addAll(chunk);
      if (bytes.length > _maxErrorBody) return null;
    }
    if (bytes.isEmpty) return null;
    try {
      return jsonDecode(utf8.decode(bytes, allowMalformed: true));
    } on FormatException {
      return null;
    }
  }
}

/// File name of a downloaded export: the `Content-Disposition` `filename`
/// (else [fallbackName]) sanitized to `[A-Za-z0-9._-]`, else
/// `keevo-export.<csv|zip|xlsx|pdf>` from the content type.
String exportFileName(
  String? contentDisposition,
  String? contentType, {
  String? fallbackName,
}) {
  String? sanitize(String? raw) {
    if (raw == null) return null;
    var cleaned = raw.trim().replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    if (cleaned.length > 120) {
      cleaned = cleaned.substring(cleaned.length - 120);
    }
    // Reject empty, dot-only or hidden names (after truncation).
    if (cleaned.isEmpty || cleaned.startsWith('.')) return null;
    return cleaned;
  }

  String? fromHeader;
  if (contentDisposition != null) {
    final match = RegExp(
            r'''filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;\s]+)''',
            caseSensitive: false)
        .firstMatch(contentDisposition);
    fromHeader = match?.group(1) ?? match?.group(2);
  }
  final name = sanitize(fromHeader) ?? sanitize(fallbackName);
  if (name != null) return name;
  final type = (contentType ?? '').toLowerCase();
  final ext = type.contains('zip')
      ? 'zip'
      : type.contains('spreadsheetml') || type.contains('excel')
          ? 'xlsx'
          : type.contains('pdf')
              ? 'pdf'
              : 'csv';
  return 'keevo-export.$ext';
}
