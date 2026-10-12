import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/export/data/remote_export_datasource.dart';
import 'package:keevo/features/export/domain/exception/export_exception.dart';
import 'package:keevo/features/export/domain/model/export_models.dart';

import '../export_test_helpers.dart';

/// v1s-18-4 — export API adapter: streamed files, queued jobs, stream error
/// bodies, criteria in the body, job file download.
void main() {
  late Directory dir;
  late FakeExportAdapter adapter;
  late RemoteExportDataSource ds;

  setUp(() async {
    dir = await Directory.systemTemp.createTemp('export_ds_test');
    adapter = FakeExportAdapter((_) => FakeHttp.json(200, {'data': []}));
    ds = RemoteExportDataSource(dio: fakeDio(adapter));
  });

  tearDown(() async {
    if (await dir.exists()) await dir.delete(recursive: true);
  });

  const csvRequest =
      ExportRequest(datasets: ['products'], format: ExportFormat.csv);

  Map<String, dynamic> errorBody(String code, [String msg = 'nope']) =>
      {'success': false, 'error': msg, 'domainCode': code};

  test('200 streams the file under exports/ with the header name', () async {
    adapter.handler = (_) => FakeHttp.file('a;b\r\n1;2\r\n',
        disposition: 'attachment; filename="keevo-products-20261012.csv"');

    final outcome = await ds.requestExport(csvRequest, dir: dir);

    final file = (outcome as ExportFileReady).file;
    final sep = Platform.pathSeparator;
    // exports/<microseconds>/<name>: own subfolder, readable name.
    expect(
        file.path,
        matches(RegExp('^${RegExp.escape('${dir.path}${sep}exports$sep')}'
            r'\d+'
            '${RegExp.escape('${sep}keevo-products-20261012.csv')}\$')));
    expect(await file.readAsString(), 'a;b\r\n1;2\r\n');
    final req = adapter.requests.single;
    expect(req.method, 'POST');
    expect(req.path, '/api/v1/exports');
    expect(adapter.bodies.single, {
      'datasets': ['products'],
      'format': 'CSV'
    });
  });

  test('missing / unsafe file names fall back to the content type', () async {
    adapter.handler =
        (_) => FakeHttp.file('PK..', contentType: 'application/zip');
    final zip = await ds.requestExport(csvRequest, dir: dir);
    expect((zip as ExportFileReady).file.path, endsWith('keevo-export.zip'));

    // Path separators never survive; a hidden / dotted name is rejected.
    expect(exportFileName('attachment; filename="../../etc/pa ss.csv"', null),
        'keevo-export.csv');
    expect(exportFileName('attachment; filename="a/b.csv"', null), 'a_b.csv');
    expect(exportFileName('attachment; filename="résumé 1.csv"', null),
        'r_sum__1.csv');
    expect(
        exportFileName(null,
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
        'keevo-export.xlsx');
    expect(exportFileName(null, 'text/csv; charset=UTF-8'), 'keevo-export.csv');
  });

  test('202 decodes the queued job from the stream', () async {
    adapter.handler = (_) => FakeHttp.json(202, {
          'success': true,
          'data': {
            'id': 'job-1',
            'status': 'PENDING',
            'format': 'XLSX',
            'datasets': ['sales'],
            'createdAt': '2026-10-12T08:00:00Z',
          },
        });

    final outcome = await ds.requestExport(
        const ExportRequest(datasets: ['sales'], format: ExportFormat.xlsx),
        dir: dir);

    final job = (outcome as ExportQueued).job;
    expect(job.id, 'job-1');
    expect(job.status, ExportJobStatus.pending);
    expect(job.datasets, ['sales']);
    expect(Directory('${dir.path}/exports').existsSync(), isFalse);
  });

  for (final (status, code, expected) in [
    (403, 'PLAN_FEATURE_REQUIRED', null),
    (
      410,
      'EXPORT_EXPIRED',
      'Ce fichier a expiré (conservé 24 h). Relancez l\'export.'
    ),
    (
      429,
      'EXPORT_QUEUE_FULL',
      'Trop d\'exports en cours, réessayez dans quelques minutes'
    ),
    (
      429,
      'RATE_LIMIT_EXCEEDED',
      'Trop d\'exports en cours, réessayez dans quelques minutes'
    ),
    (400, 'EXPORT_DATASET_UNKNOWN', 'nope'),
  ]) {
    test('$status $code error body is decoded from the stream', () async {
      adapter.handler = (_) => FakeHttp.json(status, errorBody(code));

      final error = await ds
          .requestExport(csvRequest, dir: dir)
          .then<Object?>((_) => null, onError: (Object e) => e);

      expect(error, isA<ExportException>());
      final e = error as ExportException;
      expect(e.domainCode, code);
      expect(e.statusCode, status);
      if (expected != null) expect(e.message, expected);
      expect(e.isPlanGate, code == 'PLAN_FEATURE_REQUIRED');
      expect(e.isExpired, code == 'EXPORT_EXPIRED');
    });
  }

  test('a non-JSON error body still maps by status', () async {
    adapter.handler = (_) => FakeHttp(410, [
          [60, 104, 116, 109, 108, 62]
        ]);
    final e = await ds
        .requestExport(csvRequest, dir: dir)
        .then<Object?>((_) => null, onError: (Object e) => e);
    expect((e as ExportException).isExpired, isTrue);
  });

  test('no response → NETWORK_ERROR', () async {
    adapter.handler = (_) => FakeHttp.networkError();
    final e = await ds
        .requestExport(csvRequest, dir: dir)
        .then<Object?>((_) => null, onError: (Object e) => e);
    expect((e as ExportException).domainCode, 'NETWORK_ERROR');
    expect(e.message, 'Impossible de joindre le serveur');
  });

  test('criteria are in the body only for the audit log alone', () async {
    adapter.handler = (_) => FakeHttp.file('x;y\r\n');
    final from = DateTime(2026, 10, 1);
    final to = DateTime(2026, 10, 12, 23, 59, 59, 999);
    const criteria = {
      'userId': 'u-1',
      'action': 'PRODUCT_CREATED',
      'entityType': 'Product',
      'q': 'pagne',
    };

    await ds.requestExport(
        buildExportRequest(
          selection: {'audit_log'},
          format: ExportFormat.csv,
          storeId: 's-1',
          from: from,
          to: to,
          criteria: criteria,
          datasets: testDatasets,
        ),
        dir: dir);
    await ds.requestExport(
        buildExportRequest(
          selection: {'audit_log', 'sales'},
          format: ExportFormat.csv,
          from: from,
          to: to,
          criteria: criteria,
          datasets: testDatasets,
        ),
        dir: dir);

    final sole = adapter.bodies[0] as Map<String, dynamic>;
    expect(sole['criteria'], criteria);
    expect(sole['storeId'], 's-1');
    expect(sole['from'], from.toUtc().toIso8601String());
    expect(sole['to'], to.toUtc().toIso8601String());
    final multi = adapter.bodies[1] as Map<String, dynamic>;
    expect(multi.containsKey('criteria'), isFalse);
    expect(multi['datasets'], ['sales', 'audit_log']);
  });

  test('a PDF request is never sent', () async {
    final e = await ds
        .requestExport(
            const ExportRequest(datasets: ['sales'], format: ExportFormat.pdf),
            dir: dir)
        .then<Object?>((_) => null, onError: (Object e) => e);
    expect(e, isA<ExportException>());
    expect(adapter.requests, isEmpty);
  });

  test('downloadJobFile streams GET /{id}/file; 410 is mapped', () async {
    adapter.handler = (o) => o.path.endsWith('/job-1/file')
        ? FakeHttp.file('col\r\nv\r\n',
            disposition: 'attachment; filename="keevo-sales.csv"')
        : FakeHttp.json(410, errorBody('EXPORT_EXPIRED'));
    const job =
        ExportJob(id: 'job-1', status: ExportJobStatus.ready, format: 'CSV');

    final file = await ds.downloadJobFile(job, dir: dir);
    expect(
        file.parent.parent.path, '${dir.path}${Platform.pathSeparator}exports');
    expect(file.path, endsWith('${Platform.pathSeparator}keevo-sales.csv'));
    expect(await file.readAsString(), 'col\r\nv\r\n');
    expect(adapter.requests.single.method, 'GET');

    final e = await ds
        .downloadJobFile(
            const ExportJob(
                id: 'job-2', status: ExportJobStatus.ready, format: 'CSV'),
            dir: dir)
        .then<Object?>((_) => null, onError: (Object e) => e);
    expect((e as ExportException).isExpired, isTrue);
  });

  test('listJobs sorts newest first; getDatasets parses filters', () async {
    adapter.handler = (o) => o.path.endsWith('/datasets')
        ? FakeHttp.json(200, {
            'data': [
              {
                'key': 'audit_log',
                'label': 'Journal d\'audit',
                'columns': [],
                'storeFilter': true,
                'periodFilter': true,
                'criteria': ['q', 'userId'],
              }
            ]
          })
        : FakeHttp.json(200, {
            'data': [
              {
                'id': 'old',
                'status': 'READY',
                'format': 'CSV',
                'datasets': ['sales'],
                'createdAt': '2026-10-10T08:00:00Z',
              },
              {
                'id': 'new',
                'status': 'RUNNING',
                'format': 'XLSX',
                'datasets': ['sales'],
                'createdAt': '2026-10-12T08:00:00Z',
              },
            ]
          });

    final jobs = await ds.listJobs();
    expect(jobs.map((j) => j.id), ['new', 'old']);
    final datasets = await ds.getDatasets();
    expect(datasets.single.criteria, ['q', 'userId']);
    expect(datasets.single.storeFilter, isTrue);
  });

  test('same-named downloads do not overwrite each other', () async {
    adapter.handler = (_) => FakeHttp.file('one;two\r\n',
        disposition: 'attachment; filename="same.csv"');
    final a = (await ds.requestExport(csvRequest, dir: dir)) as ExportFileReady;
    final b = (await ds.requestExport(csvRequest, dir: dir)) as ExportFileReady;
    expect(a.file.path, isNot(b.file.path));
    expect(a.file.existsSync(), isTrue);
    expect(b.file.existsSync(), isTrue);
  });

  test('exports older than 24 h are purged before a new download', () async {
    Directory('${dir.path}/exports').createSync(recursive: true);
    final stale = DateTime.now().subtract(const Duration(hours: 25));
    // A directory's mtime cannot be set from Dart: assert on a stale file
    // directly under exports/.
    final topFile = File('${dir.path}/exports/stale.csv')
      ..writeAsStringSync('x')
      ..setLastModifiedSync(stale);
    final fresh = File('${dir.path}/exports/fresh.csv')..writeAsStringSync('y');
    adapter.handler = (_) => FakeHttp.file('a;b\r\n');

    await ds.requestExport(csvRequest, dir: dir);
    expect(topFile.existsSync(), isFalse);
    expect(fresh.existsSync(), isTrue);
  });

  test('a stream failing mid-download leaves no file', () async {
    adapter.handler = (_) => FakeHttp(
        200,
        [
          [97, 59, 98]
        ],
        headers: {
          'content-disposition': ['attachment; filename="x.csv"'],
        },
        streamError: const SocketException('reset'));

    final e = await ds
        .requestExport(csvRequest, dir: dir)
        .then<Object?>((_) => null, onError: (Object e) => e);

    expect(e, isA<ExportException>());
    final exportsDir = Directory('${dir.path}/exports');
    final files = exportsDir.existsSync()
        ? exportsDir.listSync(recursive: true).whereType<File>().toList()
        : const <File>[];
    expect(files, isEmpty);
  });

  test('a file-system error maps to STORAGE_ERROR', () async {
    // `exports` is a file: the download folder cannot be created.
    File('${dir.path}/exports').writeAsStringSync('not a dir');
    adapter.handler = (_) => FakeHttp.file('a;b\r\n');

    final e = await ds
        .requestExport(csvRequest, dir: dir)
        .then<Object?>((_) => null, onError: (Object e) => e);

    expect((e as ExportException).domainCode, 'STORAGE_ERROR');
  });

  test('a truncated name starting with a dot is rejected', () {
    final dotted = 'x' * 10 + '.' + 'b' * 119; // last 120 chars start with '.'
    expect(exportFileName('attachment; filename="$dotted"', null),
        'keevo-export.csv');
  });
}
