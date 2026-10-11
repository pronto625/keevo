import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:share_plus/share_plus.dart';

import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../domain/model/csv_import_result.dart';
import '../../domain/repository/product_repository.dart';
import 'product_provider.dart';

part 'csv_import_provider.g.dart';

// ── State machine ─────────────────────────────────────────────────────────────

sealed class CsvImportState {
  const CsvImportState();
}

class CsvImportIdle extends CsvImportState {
  const CsvImportIdle();
}

class CsvImportFilePicked extends CsvImportState {
  final Uint8List bytes;
  final String fileName;
  final List<String> detectedHeaders;

  const CsvImportFilePicked({
    required this.bytes,
    required this.fileName,
    required this.detectedHeaders,
  });
}

class CsvImportLoading extends CsvImportState {
  /// File being imported — keeps the mapping step on screen.
  final CsvImportFilePicked? picked;
  const CsvImportLoading({this.picked});
}

class CsvImportSuccess extends CsvImportState {
  final CsvImportResult result;
  const CsvImportSuccess(this.result);
}

class CsvImportError extends CsvImportState {
  final String message;

  /// Set when the import itself failed: the user can fix and retry.
  final CsvImportFilePicked? picked;
  const CsvImportError(this.message, {this.picked});
}

// ── CSV parsing (header detection + preview) ─────────────────────────────────
//
// Mirrors the backend parser (Commons CSV DEFAULT: UTF-8, comma, RFC 4180
// quotes) so the headers offered in the mapping match the ones it reads.

String _decodeCsv(List<int> bytes, int maxBytes) {
  var start = 0;
  if (bytes.length >= 3 &&
      bytes[0] == 0xEF &&
      bytes[1] == 0xBB &&
      bytes[2] == 0xBF) {
    start = 3;
  }
  final end = bytes.length.clamp(start, start + maxBytes);
  // allowMalformed: the slice may cut a multi-byte character in half.
  return utf8.decode(bytes.sublist(start, end), allowMalformed: true);
}

/// Splits one CSV record, honouring double quotes and escaped `""`.
List<String> splitCsvLine(String line) {
  final fields = <String>[];
  final buf = StringBuffer();
  var inQuotes = false;
  for (var i = 0; i < line.length; i++) {
    final c = line[i];
    if (inQuotes) {
      if (c == '"') {
        if (i + 1 < line.length && line[i + 1] == '"') {
          buf.write('"');
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        buf.write(c);
      }
    } else if (c == '"') {
      inQuotes = true;
    } else if (c == ',') {
      fields.add(buf.toString().trim());
      buf.clear();
    } else {
      buf.write(c);
    }
  }
  fields.add(buf.toString().trim());
  return fields;
}

List<String> _csvLines(List<int> bytes, int maxBytes) => _decodeCsv(
        bytes, maxBytes)
    .split(RegExp(r'\r?\n'))
    .where((l) => l.trim().isNotEmpty)
    .toList();

/// Indexes of the header columns offered in the mapping: blank names are
/// dropped and a repeated name keeps its first column only (the mapping
/// dropdown needs unique values).
List<int> _keptColumns(List<String> headerFields) {
  final seen = <String>{};
  return [
    for (var i = 0; i < headerFields.length; i++)
      if (headerFields[i].isNotEmpty && seen.add(headerFields[i])) i,
  ];
}

/// Column headers from the first CSV line.
List<String> csvHeadersFromBytes(List<int> bytes) {
  final lines = _csvLines(bytes, 4096);
  if (lines.isEmpty) return const [];
  final fields = splitCsvLine(lines.first);
  return [for (final i in _keptColumns(fields)) fields[i]];
}

/// Up to [maxRows] data rows following the header line, with the same
/// columns as [csvHeadersFromBytes] so each cell sits under its header.
List<List<String>> csvPreviewRows(List<int> bytes, {int maxRows = 5}) {
  final lines = _csvLines(bytes, 8192);
  if (lines.isEmpty) return const [];
  final kept = _keptColumns(splitCsvLine(lines.first));
  return lines.skip(1).take(maxRows).map((line) {
    final cells = splitCsvLine(line);
    return [for (final i in kept) i < cells.length ? cells[i] : ''];
  }).toList();
}

// ── Notifier ──────────────────────────────────────────────────────────────────

@riverpod
class CsvImportNotifier extends _$CsvImportNotifier {
  ProductRepository get _repo => ref.read(productRepositoryProvider);

  @override
  CsvImportState build() => const CsvImportIdle();

  /// Open file picker and extract CSV headers from the first line.
  Future<void> pickFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['csv'],
        allowMultiple: false,
        withData: true,
      );
      if (result == null || result.files.isEmpty) return;

      final file = result.files.first;
      final bytes = file.bytes;
      if (bytes == null) {
        state = const CsvImportError('Impossible de lire le fichier sélectionné');
        return;
      }

      final headers = _extractHeaders(bytes);
      if (headers.isEmpty) {
        state = const CsvImportError(
            'Fichier CSV vide ou sans en-têtes détectables');
        return;
      }

      state = CsvImportFilePicked(
        bytes: bytes,
        fileName: file.name,
        detectedHeaders: headers,
      );
    } catch (e) {
      state = CsvImportError('Erreur lors de l\'ouverture du fichier : $e');
    }
  }

  /// Submit CSV bytes + column mapping to backend.
  Future<void> runImport({required Map<String, String> mapping}) async {
    final current = switch (state) {
      final CsvImportFilePicked picked => picked,
      CsvImportError(:final picked?) => picked,
      _ => null,
    };
    if (current == null) return;

    state = CsvImportLoading(picked: current);
    try {
      final result = await _repo.importCsv(
        csvBytes: current.bytes,
        fileName: current.fileName,
        columnMapping: mapping,
        storeId: ref.read(activeStoreIdProvider),
      );
      state = CsvImportSuccess(result);
    } catch (e) {
      state = CsvImportError(_friendlyError(e), picked: current);
    }
  }

  /// Download the CSV template and open the native share sheet so the user
  /// can save it wherever they like (Downloads, Drive, WhatsApp, etc.).
  ///
  /// Writing directly to the public Downloads folder is blocked by Android's
  /// scoped storage (API 29+) without extra permissions the app doesn't
  /// request — a direct write silently lands in the app's private cache
  /// instead, which looks like a success but leaves no file the user can
  /// find. Delegating to [Share.shareXFiles] hands the save location to the
  /// OS, which works on every platform without any storage permission.
  ///
  /// Returns true once the template was fetched and the share sheet opened,
  /// false on failure (e.g. no connectivity).
  Future<bool> downloadTemplate() async {
    try {
      final bytes = await _repo.downloadCsvTemplate();
      const fileName = 'keevo_import_template.csv';
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/$fileName');
      await file.writeAsBytes(bytes);
      await Share.shareXFiles(
        [XFile(file.path, mimeType: 'text/csv')],
        subject: fileName,
      );
      return true;
    } catch (e) {
      return false;
    }
  }

  /// Reset to idle state.
  void reset() => state = const CsvImportIdle();

  // ── Helpers ────────────────────────────────────────────────────────────────

  List<String> _extractHeaders(Uint8List bytes) => csvHeadersFromBytes(bytes);

  String _friendlyError(Object e) {
    final msg = e.toString();
    if (msg.contains('UNAUTHORIZED')) return 'Session expirée — veuillez vous reconnecter';
    if (msg.contains('FORBIDDEN')) return 'Seul le propriétaire peut importer des produits';
    if (msg.contains('CSV_PARSE_ERROR')) return 'Fichier CSV invalide ou illisible';
    if (msg.contains('NETWORK_ERROR') || msg.contains('SocketException')) {
      return 'Connexion requise pour l\'import CSV';
    }
    return 'Erreur lors de l\'import : $e';
  }
}
