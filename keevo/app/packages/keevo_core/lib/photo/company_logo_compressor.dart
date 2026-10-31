import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

/// A compressed company logo: the bytes and whether they are a PNG.
class CompressedLogo {
  const CompressedLogo(this.bytes, {required this.png});

  final Uint8List bytes;
  final bool png;
}

/// Turns a picked image into the company logo sent to the server (Story 14.2)
/// — same approach as [ProductPhotoCompressor]: longest side [maxDimension]
/// px, EXIF orientation applied, every metadata block dropped.
///
/// A logo with transparency stays a PNG (it is drawn on white invoices and
/// export headers); when that PNG is over [targetBytes], or the image is
/// opaque, it becomes a JPEG (quality lowered until under [targetBytes]).
class CompanyLogoCompressor {
  const CompanyLogoCompressor();

  static const int maxDimension = 600;
  static const int quality = 85;
  static const int minQuality = 50;

  /// Under the server limit (200 KB) with a margin.
  static const int targetBytes = 190 * 1024;

  /// `ImagePicker.pickImage` parameters (first, native stage).
  static const double pickerMaxDimension = 1200;
  static const int pickerQuality = 90;

  Future<CompressedLogo> compress(Uint8List source) =>
      compute(compressSync, source, debugLabel: 'companyLogoCompress');

  /// Throws [FormatException] when [source] is not an image.
  static CompressedLogo compressSync(Uint8List source) {
    img.Image? decoded;
    try {
      decoded = img.decodeImage(source);
    } catch (_) {
      decoded = null; // corrupt bytes: the decoders throw RangeError…
    }
    if (decoded == null) {
      throw const FormatException('Image illisible');
    }
    var image = img.bakeOrientation(decoded);
    image.exif = img.ExifData();
    final longest = image.width > image.height ? image.width : image.height;
    if (longest > maxDimension) {
      image = image.width >= image.height
          ? img.copyResize(image,
              width: maxDimension, interpolation: img.Interpolation.average)
          : img.copyResize(image,
              height: maxDimension, interpolation: img.Interpolation.average);
      image.exif = img.ExifData();
    }
    if (image.hasAlpha) {
      final png = img.encodePng(image, level: 9);
      if (png.length <= targetBytes) return CompressedLogo(png, png: true);
    }
    // Opaque JPEG: transparent pixels over white.
    final canvas = img.Image(width: image.width, height: image.height);
    img.fill(canvas, color: img.ColorRgb8(255, 255, 255));
    img.compositeImage(canvas, image);
    var q = quality;
    var out = img.encodeJpg(canvas, quality: q);
    while (out.length > targetBytes && q > minQuality) {
      q -= 10;
      out = img.encodeJpg(canvas, quality: q);
    }
    return CompressedLogo(out, png: false);
  }
}
