import 'dart:io';

import '../../domain/exception/export_exception.dart';
import '../../domain/local/csv_cells.dart';
import '../../domain/local/local_export_schema.dart';
import '../export_temp_files.dart';
import 'local_csv_writer.dart';
import 'local_export_reader.dart';

/// LocalExportService — on-device CSV export, no network (v1s-18-5).
///
/// Reads every selected dataset page by page ([LocalExportReader], main
/// isolate) and hands each page to the worker-isolate [LocalCsvWriter]. The
/// file lands in its own folder `<temp>/exports/<µs>/`; the folder is deleted
/// when anything fails.
class LocalExportService {
  final LocalExportReader _reader;
  final LocalCsvWriter Function() _newWriter;
  final int pageSize;

  LocalExportService(
    this._reader, {
    LocalCsvWriter Function()? writerFactory,
    this.pageSize = 500,
  }) : _newWriter = writerFactory ?? LocalCsvWriter.new;

  /// Writes [datasets] (catalogue order, unknown keys dropped) matching
  /// [filter] and returns the file: a `.csv` for one dataset, a `.zip` of
  /// `<key>.csv` entries otherwise. [onProgress] gets the running row count.
  ///
  /// Throws [ExportException.storage] on a file system failure,
  /// [ExportException.localFailed] on any other failure.
  Future<File> export({
    required Set<String> datasets,
    required LocalExportFilter filter,
    required Directory tempDir,
    void Function(int rows)? onProgress,
    DateTime? now,
  }) async {
    final selected = [
      for (final d in localExportCatalog)
        if (datasets.contains(d.key)) d,
    ];
    if (selected.isEmpty) throw ArgumentError.value(datasets, 'datasets');

    final sep = Platform.pathSeparator;
    final exportsDir = Directory('${tempDir.path}${sep}exports');
    final target = Directory(
        '${exportsDir.path}$sep${DateTime.now().microsecondsSinceEpoch}');
    final writer = _newWriter();
    var rows = 0;
    try {
      await target.create(recursive: true);
      await purgeOldExports(exportsDir, keep: target.path);
      await writer.start(target);
      for (final d in selected) {
        await writer.beginDataset(d.key, d.columns);
        await for (final page
            in _reader.pages(d.key, filter, pageSize: pageSize)) {
          await writer.addRows(page);
          rows += page.length;
          onProgress?.call(rows);
        }
        await writer.endDataset();
      }
      return await writer.finish(localExportFileName(
        selected.length == 1 ? selected.single.key : null,
        now ?? DateTime.now(),
      ));
    } catch (e) {
      await writer.abort();
      try {
        if (await target.exists()) await target.delete(recursive: true);
      } catch (_) {}
      if (e is ExportException) rethrow;
      if (e is FileSystemException) throw ExportException.storage;
      throw ExportException.localFailed;
    }
  }
}

/// `keevo-donnees-appareil-<label slug|export>-<yyyyMMdd-HHmm WAT>.<csv|zip>`
/// (`ventes`, never the dataset key — v1s-16-20) — the
/// « données de cet appareil » marking travels in the file name (also the
/// share subject). [singleKey] is `null` for a multi-dataset zip.
String localExportFileName(String? singleKey, DateTime now) {
  final w = toWat(now);
  String two(int v) => v.toString().padLeft(2, '0');
  final stamp = '${w.year}${two(w.month)}${two(w.day)}-'
      '${two(w.hour)}${two(w.minute)}';
  final ext = singleKey == null ? 'zip' : 'csv';
  final name = singleKey == null ? 'export' : exportDatasetSlug(singleKey);
  return 'keevo-donnees-appareil-$name-$stamp.$ext';
}
