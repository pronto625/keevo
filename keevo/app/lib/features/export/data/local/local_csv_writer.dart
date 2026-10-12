import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:archive/archive_io.dart';

import '../../domain/exception/export_exception.dart';
import '../../domain/local/csv_cells.dart';
import '../../domain/local/local_export_schema.dart';

/// LocalCsvWriter — formats and writes the on-device CSV in a long-lived
/// worker isolate (v1s-18-5).
///
/// The main isolate only reads pages from Drift and sends them here as rows
/// of sendable primitives; cell formatting, file I/O and the zip of several
/// datasets happen in the worker, so a 10,000-row export never janks the UI.
///
/// Protocol: one request at a time, each awaited (back-pressure: a page is
/// written before the next one is read). Each dataset goes to
/// `<dir>/.parts/<key>.csv`; [finish] moves the only one to the final name,
/// or zips them all (`<key>.csv` entries, catalogue order).
///
/// A [FileSystemException] in the worker surfaces as
/// [ExportException.storage].
class LocalCsvWriter {
  Isolate? _isolate;
  ReceivePort? _receive;
  SendPort? _send;
  StreamSubscription<Object?>? _sub;
  Completer<Object?>? _pending;

  /// Spawns the worker; files are written under [dir].
  Future<void> start(Directory dir) async {
    if (_isolate != null) throw StateError('LocalCsvWriter already started');
    final receive = ReceivePort();
    _receive = receive;
    final ready = Completer<SendPort>();
    _sub = receive.listen((message) {
      if (!ready.isCompleted && message is SendPort) {
        ready.complete(message);
        return;
      }
      final pending = _pending;
      _pending = null;
      pending?.complete(message);
    });
    _isolate = await Isolate.spawn(_workerMain, receive.sendPort,
        debugName: 'local-csv-writer');
    _send = await ready.future;
    await _call(['start', dir.path]);
  }

  /// Opens `<key>.csv` and writes the BOM and the header row.
  Future<void> beginDataset(String key, List<LocalExportColumn> columns) =>
      _call([
        'begin',
        key,
        [for (final c in columns) c.label],
        [for (final c in columns) c.type.index],
      ]);

  /// Formats and appends [rows] (sendable primitives only).
  Future<void> addRows(List<List<Object?>> rows) => _call(['rows', rows]);

  /// Flushes and closes the current dataset file.
  Future<void> endDataset() => _call(['end']);

  /// Final file `<dir>/<fileName>`: the single CSV, or a zip of every
  /// dataset. Stops the worker.
  Future<File> finish(String fileName) async {
    final path = await _call(['finish', fileName]) as String;
    _dispose();
    return File(path);
  }

  /// Stops the worker without producing a file (the caller deletes the
  /// folder).
  Future<void> abort() async {
    final send = _send;
    if (send != null && _pending == null) {
      try {
        await _call(['abort']).timeout(const Duration(seconds: 5));
      } catch (_) {}
    }
    _dispose();
  }

  Future<Object?> _call(List<Object?> message) async {
    final send = _send;
    if (send == null) throw StateError('LocalCsvWriter not started');
    if (_pending != null) throw StateError('LocalCsvWriter is busy');
    final completer = Completer<Object?>();
    _pending = completer;
    send.send(message);
    final reply = await completer.future;
    if (reply is List && reply.isNotEmpty && reply.first == 'err') {
      if (reply[1] == 'fs') throw ExportException.storage;
      throw StateError('LocalCsvWriter: ${reply[2]}');
    }
    return reply is List && reply.length > 1 ? reply[1] : null;
  }

  void _dispose() {
    _sub?.cancel();
    _sub = null;
    _receive?.close();
    _receive = null;
    _isolate?.kill(priority: Isolate.immediate);
    _isolate = null;
    _send = null;
    final pending = _pending;
    _pending = null;
    if (pending != null && !pending.isCompleted) {
      pending.complete(['err', 'aborted', 'writer stopped']);
    }
  }
}

// ── Worker isolate ───────────────────────────────────────────────────────────

void _workerMain(SendPort reply) {
  final port = ReceivePort();
  reply.send(port.sendPort);
  final worker = _Worker();
  port.listen((message) async {
    final m = message as List;
    try {
      final result = await worker.handle(m);
      reply.send(['ok', result]);
      if (m.first == 'finish' || m.first == 'abort') port.close();
    } on FileSystemException catch (e) {
      await worker.closeQuietly();
      reply.send(['err', 'fs', e.toString()]);
    } catch (e) {
      await worker.closeQuietly();
      reply.send(['err', 'other', e.toString()]);
    }
  });
}

class _Worker {
  String? _dir;
  IOSink? _sink;
  List<LocalColumnType> _types = const [];
  final _parts = <(String key, String path)>[];

  String get _partsDir => '$_dir${Platform.pathSeparator}.parts';

  Future<Object?> handle(List m) async {
    switch (m.first as String) {
      case 'start':
        _dir = m[1] as String;
        await Directory(_partsDir).create(recursive: true);
        return null;
      case 'begin':
        final key = m[1] as String;
        final labels = (m[2] as List).cast<String>();
        _types = [
          for (final i in (m[3] as List).cast<int>()) LocalColumnType.values[i]
        ];
        final path = '$_partsDir${Platform.pathSeparator}$key.csv';
        final sink = File(path).openWrite(encoding: utf8);
        _sink = sink;
        _parts.add((key, path));
        sink
          ..write(csvBom)
          ..write(csvRecord(labels));
        await sink.flush();
        return null;
      case 'rows':
        final sink = _sink!;
        final cells = List<String>.filled(_types.length, '');
        for (final row in m[1] as List) {
          final values = row as List;
          for (var i = 0; i < _types.length; i++) {
            cells[i] =
                formatCsvCell(_types[i], i < values.length ? values[i] : null);
          }
          sink.write(csvRecord(cells));
        }
        await sink.flush();
        return null;
      case 'end':
        final sink = _sink!;
        _sink = null;
        await sink.flush();
        await sink.close();
        return null;
      case 'finish':
        final target = '$_dir${Platform.pathSeparator}${m[1] as String}';
        if (_parts.length == 1) {
          await File(_parts.single.$2).rename(target);
        } else {
          final encoder = ZipFileEncoder();
          encoder.create(target);
          for (final (key, path) in _parts) {
            await encoder.addFile(File(path), '$key.csv');
          }
          await encoder.close();
        }
        await Directory(_partsDir).delete(recursive: true);
        return target;
      case 'abort':
        await closeQuietly();
        return null;
    }
    throw ArgumentError.value(m.first, 'command');
  }

  Future<void> closeQuietly() async {
    final sink = _sink;
    _sink = null;
    try {
      await sink?.close();
    } catch (_) {}
  }
}
