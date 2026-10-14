import 'dart:convert';
import 'dart:developer' as dev;
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

import '../photo/product_photo_compressor.dart';
import '../photo/product_photo_files.dart';
import '../storage/app_database.dart';
import 'account_read_only_exception.dart';

/// Product photo part of the offline outbox (FR21).
///
/// A photo is not a JSON operation of `POST /api/v1/sync/push`: it is a file,
/// sent by its own multipart endpoint. Its queue entries
/// ([uploadOperation] / [deleteOperation], `entityId` = product id) live in
/// `sync_queue` like the others — kept offline, retried, counted as pending —
/// but [RestSyncService] leaves them out of the JSON batches and hands them to
/// [processPending] once the batches are sent, so a photo never holds back a
/// sale, and waits for its own product creation to be on the server.
class ProductPhotoOutbox {
  ProductPhotoOutbox({
    required AppDatabase database,
    required Dio dio,
    ProductPhotoFiles? files,
    SharedPreferences? prefs,
    ProductPhotoCompressor compressor = const ProductPhotoCompressor(),
  })  : _db = database,
        _dio = dio,
        _files = files ?? ProductPhotoFiles(),
        _prefs = prefs,
        _compressor = compressor;

  final AppDatabase _db;
  final Dio _dio;
  final ProductPhotoFiles _files;
  final SharedPreferences? _prefs;
  final ProductPhotoCompressor _compressor;

  static const uploadOperation = 'UPLOAD_PRODUCT_PHOTO';
  static const deleteOperation = 'DELETE_PRODUCT_PHOTO';
  static const operations = {uploadOperation, deleteOperation};

  /// SharedPreferences flag of the one-time upload of photos taken before
  /// photos were stored on the server (they were only on the phone).
  static const migrationDoneKey = 'product_photos_server_migration_v1';

  static bool isPhotoOperation(String operation) =>
      operations.contains(operation);

  static const _createOperations = ['CREATE_PRODUCT', 'CREATE_DRAFT_PRODUCT'];

  // ── Queueing ────────────────────────────────────────────────────────────

  /// Removes the photo operations still queued for [productId] (a newer photo
  /// or a deletion supersedes them). Returns the local files they referenced.
  Future<List<String>> dropQueued(String productId) async {
    final ops = await (_db.select(_db.syncQueue)
          ..where((t) =>
              t.synced.equals(false) &
              t.entityId.equals(productId) &
              t.operation.isIn(operations)))
        .get();
    final paths = <String>[];
    for (final op in ops) {
      final path = _payload(op)['localPath'];
      if (path is String) paths.add(path);
    }
    if (ops.isNotEmpty) {
      await (_db.delete(_db.syncQueue)
            ..where((t) => t.id.isIn(ops.map((o) => o.id).toList())))
          .go();
    }
    return paths;
  }

  /// Queues the upload of the thumbnail at [localPath] for [productId] and
  /// makes it the product photo on this device.
  Future<void> enqueueUpload(String productId, String localPath) async {
    final superseded = await _db.transaction(() async {
      final previous = await _currentPhoto(productId);
      final dropped = await dropQueued(productId);
      await _db.customStatement(
          'UPDATE products SET photo_url = ? WHERE id = ?', [localPath, productId]);
      await _insert(uploadOperation, productId, {
        'productId': productId,
        'localPath': localPath,
      });
      return [...dropped, if (previous != null) previous];
    });
    await _deleteLocalFiles(superseded, keep: localPath);
  }

  /// « Supprimer la photo »: the product shows its initials at once on this
  /// device; the server photo is deleted on the next push.
  Future<void> enqueueDelete(String productId) async {
    final superseded = await _db.transaction(() async {
      final previous = await _currentPhoto(productId);
      final dropped = await dropQueued(productId);
      await _db.customStatement(
          'UPDATE products SET photo_url = NULL WHERE id = ?', [productId]);
      await _insert(deleteOperation, productId, {'productId': productId});
      return [...dropped, if (previous != null) previous];
    });
    await _deleteLocalFiles(superseded);
  }

  Future<String?> _currentPhoto(String productId) async {
    final row = await _db
        .customSelect('SELECT photo_url FROM products WHERE id = ?',
            variables: [Variable<String>(productId)])
        .getSingleOrNull();
    return row?.read<String?>('photo_url');
  }

  /// Device thumbnails no longer referenced (server photos' cache files are
  /// kept: other screens may still show that version until the next pull).
  Future<void> _deleteLocalFiles(List<String> paths, {String? keep}) async {
    for (final path in paths.toSet()) {
      if (path == keep || !ProductPhotoFiles.isLocalPath(path)) continue;
      await _files.deleteQuietly(path);
    }
  }

  Future<void> _insert(
      String operation, String productId, Map<String, dynamic> payload) {
    return _db.into(_db.syncQueue).insert(SyncQueueCompanion.insert(
          id: const Uuid().v4(),
          operation: operation,
          payload: jsonEncode(payload),
          createdAt: DateTime.now(),
          entityId: Value(productId),
        ));
  }

  // ── One-time migration of local-only photos ─────────────────────────────

  /// Photos taken before this version only exist on the phone (`photoUrl` =
  /// a local file path). Once, each one whose file still exists is turned
  /// into a thumbnail and queued for upload, so they are not lost on a
  /// reinstall and the other devices get them. Safe offline: it only queues.
  Future<void> migrateLegacyPhotosOnce() async {
    final prefs = _prefs;
    if (prefs == null || (prefs.getBool(migrationDoneKey) ?? false)) return;
    final rows = await _db
        .customSelect('SELECT id, photo_url FROM products '
            "WHERE photo_url IS NOT NULL AND photo_url <> ''")
        .get();
    for (final row in rows) {
      final id = row.read<String>('id');
      final url = row.read<String>('photo_url');
      if (!ProductPhotoFiles.isLocalPath(url)) continue;
      // Already queued (taken with this version, upload pending).
      final queued = await (_db.select(_db.syncQueue)
            ..where((t) =>
                t.synced.equals(false) &
                t.entityId.equals(id) &
                t.operation.isIn(operations)))
          .get();
      if (queued.isNotEmpty) continue;
      final file = File(ProductPhotoFiles.localFilePath(url));
      if (!file.existsSync()) continue;
      try {
        final thumb = await _compressor.compress(await file.readAsBytes());
        final path = await _files.savePending(id, thumb);
        await enqueueUpload(id, path);
        await _files.deleteQuietly(url);
      } catch (e) {
        dev.log('Legacy photo of $id skipped: $e', name: 'ProductPhoto');
      }
    }
    await prefs.setBool(migrationDoneKey, true);
  }

  // ── Sending ─────────────────────────────────────────────────────────────

  /// Sends the queued photo operations, oldest first. Never throws: a
  /// network failure leaves them queued (retried on the next push); a
  /// definitive refusal (product gone, photo refused, draft of another role)
  /// drops the operation — the photo stays on this device only.
  Future<void> processPending() async {
    final ops = await (_db.select(_db.syncQueue)
          ..where((t) =>
              t.synced.equals(false) &
              t.quarantinedAt.isNull() &
              t.operation.isIn(operations))
          ..orderBy([(t) => OrderingTerm.asc(t.createdAt)]))
        .get();
    for (final op in ops) {
      final productId = op.entityId;
      if (productId == null) {
        await _remove(op.id);
        continue;
      }
      // The product must exist on the server first.
      if (await _creationPending(productId)) continue;
      try {
        if (op.operation == uploadOperation) {
          await _upload(op, productId);
        } else {
          await _dio.delete<dynamic>('/api/v1/products/$productId/photo');
          await _remove(op.id);
        }
      } on DioException catch (e) {
        if (_isTransient(e)) {
          await (_db.update(_db.syncQueue)..where((t) => t.id.equals(op.id)))
              .write(SyncQueueCompanion(
            retryCount: Value(op.retryCount + 1),
            lastAttemptAt: Value(DateTime.now()),
          ));
          dev.log('Photo ${op.operation} of $productId postponed: '
              '${e.response?.statusCode ?? e.type}', name: 'ProductPhoto');
          return; // offline / server unavailable: the rest waits too
        }
        dev.log('Photo ${op.operation} of $productId refused: '
            '${e.response?.statusCode}', name: 'ProductPhoto');
        await _remove(op.id);
      } catch (e) {
        dev.log('Photo ${op.operation} of $productId failed: $e',
            name: 'ProductPhoto');
        await (_db.update(_db.syncQueue)..where((t) => t.id.equals(op.id)))
            .write(SyncQueueCompanion(
          retryCount: Value(op.retryCount + 1),
          lastAttemptAt: Value(DateTime.now()),
        ));
      }
    }
  }

  Future<void> _upload(SyncQueueData op, String productId) async {
    final localPath = _payload(op)['localPath'];
    final file = localPath is String
        ? File(ProductPhotoFiles.localFilePath(localPath))
        : null;
    if (file == null || !file.existsSync()) {
      await _remove(op.id); // nothing left to send
      return;
    }
    final bytes = await file.readAsBytes();
    final response = await _dio.post<Map<String, dynamic>>(
      '/api/v1/products/$productId/photo',
      data: FormData.fromMap({
        'file': MultipartFile.fromBytes(bytes,
            filename: 'photo.jpg', contentType: DioMediaType('image', 'jpeg')),
      }),
    );
    final url = (response.data?['data'] as Map?)?['photoUrl'] as String?;
    if (url == null) throw StateError('No photoUrl in the upload response');

    // The product photo is now the server one — unless it changed meanwhile
    // (newer photo or deletion, queued after this one).
    final stillCurrent = await _currentPhoto(productId) == localPath;
    if (stillCurrent) {
      await _files.adoptAsCache(localPath as String, url);
      await _db.customStatement(
          'UPDATE products SET photo_url = ? WHERE id = ?', [url, productId]);
    } else {
      await _files.deleteQuietly(localPath as String);
    }
    await _remove(op.id);
  }

  Future<bool> _creationPending(String productId) async {
    final rows = await (_db.select(_db.syncQueue)
          ..where((t) =>
              t.synced.equals(false) &
              t.entityId.equals(productId) &
              t.operation.isIn(_createOperations))
          ..limit(1))
        .get();
    return rows.isNotEmpty;
  }

  static bool _isTransient(DioException e) {
    final status = e.response?.statusCode;
    if (status == null) return true; // no network / timeout
    if (status == 401 || status == 408 || status == 423 || status == 429) {
      return true;
    }
    if (status >= 500) return true;
    final body = e.response?.data;
    // A read-only account (suspended, deletion pending): kept until it lifts.
    if (status == 403 &&
        body is Map &&
        AccountReadOnlyException.forDomainCode(body['domainCode'] as String?) !=
            null) {
      return true;
    }
    return false;
  }

  Future<void> _remove(String opId) =>
      (_db.delete(_db.syncQueue)..where((t) => t.id.equals(opId))).go();

  static Map<String, dynamic> _payload(SyncQueueData op) {
    try {
      return jsonDecode(op.payload) as Map<String, dynamic>;
    } catch (_) {
      return const {};
    }
  }
}
