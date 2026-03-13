import 'dart:io';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

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
  const CsvImportLoading();
}

class CsvImportSuccess extends CsvImportState {
  final CsvImportResult result;
  const CsvImportSuccess(this.result);
}

class CsvImportError extends CsvImportState {
  final String message;
  const CsvImportError(this.message);
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
    final current = state;
    if (current is! CsvImportFilePicked) return;

    state = const CsvImportLoading();
    try {
      final result = await _repo.importCsv(
        csvBytes: current.bytes,
        fileName: current.fileName,
        columnMapping: mapping,
      );
      state = CsvImportSuccess(result);
    } catch (e) {
      state = CsvImportError(_friendlyError(e));
    }
  }

  /// Download CSV template and save it to the Downloads folder (or Documents on
  /// platforms where Downloads is unavailable). Returns the saved file path on
  /// success, or null on failure.
  Future<String?> downloadTemplate() async {
    try {
      final bytes = await _repo.downloadCsvTemplate();
      const fileName = 'keevo_import_template.csv';
      Directory dir;
      if (!kIsWeb && Platform.isAndroid) {
        // Public Downloads folder — accessible without extra permission on API 29+
        dir = Directory('/storage/emulated/0/Download');
        if (!dir.existsSync()) dir = await getTemporaryDirectory();
      } else {
        dir = (await getDownloadsDirectory()) ?? await getApplicationDocumentsDirectory();
      }
      final file = File('${dir.path}/$fileName');
      await file.writeAsBytes(bytes);
      return file.path;
    } catch (e) {
      return null;
    }
  }

  /// Reset to idle state.
  void reset() => state = const CsvImportIdle();

  // ── Helpers ────────────────────────────────────────────────────────────────

  /// Extract column headers from first line of CSV bytes.
  ///
  /// Strips UTF-8 BOM if present, then splits on the first newline.
  List<String> _extractHeaders(Uint8List bytes) {
    // Strip UTF-8 BOM (EF BB BF)
    int start = 0;
    if (bytes.length >= 3 &&
        bytes[0] == 0xEF &&
        bytes[1] == 0xBB &&
        bytes[2] == 0xBF) {
      start = 3;
    }

    // Decode up to first 4 KB
    final slice = bytes.sublist(start, bytes.length.clamp(start, start + 4096));
    final text = String.fromCharCodes(slice);

    final firstLine = text.split(RegExp(r'\r?\n')).first;
    return firstLine
        .split(',')
        .map((h) => h.trim().replaceAll('"', ''))
        .where((h) => h.isNotEmpty)
        .toList();
  }

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
