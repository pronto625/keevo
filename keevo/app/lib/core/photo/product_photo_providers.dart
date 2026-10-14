import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';
import '../di/providers.dart';
import '../sync/product_photo_outbox.dart';
import 'product_photo_cache.dart';
import 'product_photo_compressor.dart';
import 'product_photo_files.dart';

/// Camera / gallery picker of product photos (overridden in tests).
final productPhotoPickerProvider =
    Provider<ImagePicker>((ref) => ImagePicker());

/// On-device photo files (documents directory).
final productPhotoFilesProvider =
    Provider<ProductPhotoFiles>((ref) => ProductPhotoFiles());

/// Thumbnail compression of picked photos.
final productPhotoCompressorProvider =
    Provider<ProductPhotoCompressor>((ref) => const ProductPhotoCompressor());

/// Display cache of server photos (authenticated download).
final productPhotoCacheProvider = Provider<ProductPhotoCache>((ref) {
  return ProductPhotoCache(
    dio: ref.watch(dioProvider),
    files: ref.watch(productPhotoFilesProvider),
  );
});

/// Queue of photo uploads / deletions (sync outbox).
final productPhotoOutboxProvider = Provider<ProductPhotoOutbox>((ref) {
  return ProductPhotoOutbox(
    database: ref.watch(appDatabaseProvider),
    dio: ref.watch(dioProvider),
    files: ref.watch(productPhotoFilesProvider),
    prefs: ref.watch(sharedPreferencesProvider),
    compressor: ref.watch(productPhotoCompressorProvider),
  );
});
