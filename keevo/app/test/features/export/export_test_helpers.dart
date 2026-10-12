import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'package:keevo/features/export/data/remote_export_datasource.dart';
import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';

/// A scripted HTTP response of [FakeExportAdapter].
class FakeHttp {
  final int status;
  final List<List<int>> chunks;
  final Map<String, List<String>> headers;
  final Object? error;

  /// Error raised by the body stream after [chunks] (mid-download failure).
  final Object? streamError;

  FakeHttp(this.status, this.chunks,
      {this.headers = const {}, this.error, this.streamError});

  factory FakeHttp.json(int status, Object body) => FakeHttp(
        status,
        [utf8.encode(jsonEncode(body))],
        headers: {
          Headers.contentTypeHeader: ['application/json'],
        },
      );

  factory FakeHttp.file(String content,
          {String? disposition, String contentType = 'text/csv'}) =>
      FakeHttp(
        200,
        // Two chunks: the body is streamed, not read at once.
        [
          utf8.encode(content.substring(0, content.length ~/ 2)),
          utf8.encode(content.substring(content.length ~/ 2)),
        ],
        headers: {
          Headers.contentTypeHeader: [contentType],
          if (disposition != null) 'content-disposition': [disposition],
        },
      );

  factory FakeHttp.networkError() =>
      FakeHttp(0, const [], error: const SocketException('unreachable'));
}

/// Real-Dio adapter returning scripted responses; records each request.
class FakeExportAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  final bodies = <Object?>[];
  FakeHttp Function(RequestOptions options) handler;

  FakeExportAdapter(this.handler);

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requests.add(options);
    bodies.add(options.data);
    final r = handler(options);
    if (r.error != null) {
      throw DioException(
        requestOptions: options,
        type: DioExceptionType.connectionError,
        error: r.error,
      );
    }
    Stream<Uint8List> body() async* {
      for (final c in r.chunks) {
        yield Uint8List.fromList(c);
      }
      if (r.streamError != null) throw r.streamError!;
    }

    return ResponseBody(
      body(),
      r.status,
      headers: r.headers,
    );
  }

  @override
  void close({bool force = false}) {}
}

Dio fakeDio(FakeExportAdapter adapter) =>
    Dio(BaseOptions(baseUrl: 'https://api.test'))..httpClientAdapter = adapter;

const testDatasets = [
  ExportDataset(
      key: 'products',
      label: 'Produits',
      storeFilter: false,
      periodFilter: false),
  ExportDataset(
      key: 'sales',
      label: 'Ventes',
      storeFilter: true,
      periodFilter: true,
      pdfTemplate: 'Liste des ventes'),
  ExportDataset(
      key: 'audit_log',
      label: 'Journal d\'audit',
      storeFilter: true,
      periodFilter: true,
      criteria: ['action', 'entityId', 'entityType', 'q', 'userId']),
];

/// [testDatasets] plus « Stocks », whose PDF template « État de stock » is a
/// snapshot (store filter, no period) — v1s-18-3.
const pdfTestDatasets = [
  ...testDatasets,
  ExportDataset(
      key: 'stock_levels',
      label: 'Stocks',
      storeFilter: true,
      periodFilter: false,
      pdfTemplate: 'État de stock'),
];

/// Scriptable fake for page tests (no HTTP).
class FakeExportDataSource implements RemoteExportDataSource {
  List<ExportDataset> datasets = testDatasets;
  Future<List<ExportJob>> Function()? onList;
  Future<ExportOutcome> Function(ExportRequest r)? onRequest;
  Future<File> Function(ExportJob job)? onDownload;

  final requests = <ExportRequest>[];
  final downloads = <String>[];
  int listCalls = 0;
  Object? datasetsError;

  @override
  Future<List<ExportDataset>> getDatasets() async {
    if (datasetsError != null) throw datasetsError!;
    return datasets;
  }

  @override
  Future<List<ExportJob>> listJobs() {
    listCalls++;
    return onList?.call() ?? Future.value(const []);
  }

  @override
  Future<ExportJob> getJob(String id) => throw UnimplementedError();

  @override
  Future<ExportOutcome> requestExport(ExportRequest request,
      {required Directory dir}) {
    requests.add(request);
    return onRequest?.call(request) ?? Future.error(ExportException.unexpected);
  }

  @override
  Future<File> downloadJobFile(ExportJob job, {required Directory dir}) {
    downloads.add(job.id);
    return onDownload?.call(job) ?? Future.error(ExportException.unexpected);
  }
}
