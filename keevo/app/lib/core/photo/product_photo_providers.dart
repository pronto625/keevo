import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/photo/company_logo_compressor.dart';
import 'package:keevo_core/photo/product_photo_cache.dart';
import 'package:keevo_core/photo/product_photo_compressor.dart';
import 'package:keevo_core/photo/product_photo_files.dart';
import 'package:keevo_core/sync/product_photo_outbox.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';

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

/// Company logo compression (Story 14.2) — picked with
/// [productPhotoPickerProvider].
final companyLogoCompressorProvider =
    Provider<CompanyLogoCompressor>((ref) => const CompanyLogoCompressor());
