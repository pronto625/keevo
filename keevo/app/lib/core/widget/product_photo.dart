import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../photo/product_photo_files.dart';
import '../photo/product_photo_providers.dart';

/// A product photo thumbnail (catalogue, POS grid, inventory rows) or
/// [placeholder] (the initials) when there is none or it cannot be shown.
///
/// Order: the file on this device (taken here, not uploaded yet), else the
/// local copy of the server photo, downloaded once with the authenticated
/// client and kept for offline use.
class ProductPhoto extends StatelessWidget {
  const ProductPhoto({
    super.key,
    required this.photoUrl,
    required this.placeholder,
    this.fit = BoxFit.cover,
  });

  final String? photoUrl;
  final Widget placeholder;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    final url = photoUrl;
    if (url == null || url.isEmpty) return placeholder;
    if (ProductPhotoFiles.isLocalPath(url)) {
      final file = File(ProductPhotoFiles.localFilePath(url));
      if (!file.existsSync()) return placeholder;
      return _image(file);
    }
    if (ProductPhotoFiles.isServerUrl(url)) {
      return _ServerProductPhoto(
          url: url, placeholder: placeholder, imageBuilder: _image);
    }
    return placeholder;
  }

  Widget _image(File file) => Image.file(
        file,
        fit: fit,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => placeholder,
      );
}

class _ServerProductPhoto extends ConsumerStatefulWidget {
  const _ServerProductPhoto({
    required this.url,
    required this.placeholder,
    required this.imageBuilder,
  });

  final String url;
  final Widget placeholder;
  final Widget Function(File file) imageBuilder;

  @override
  ConsumerState<_ServerProductPhoto> createState() =>
      _ServerProductPhotoState();
}

class _ServerProductPhotoState extends ConsumerState<_ServerProductPhoto> {
  late Future<File?> _file;

  @override
  void initState() {
    super.initState();
    _file = ref.read(productPhotoCacheProvider).resolve(widget.url);
  }

  @override
  void didUpdateWidget(covariant _ServerProductPhoto oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      _file = ref.read(productPhotoCacheProvider).resolve(widget.url);
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<File?>(
      future: _file,
      builder: (context, snapshot) {
        final file = snapshot.data;
        if (file == null) return widget.placeholder;
        return widget.imageBuilder(file);
      },
    );
  }
}
