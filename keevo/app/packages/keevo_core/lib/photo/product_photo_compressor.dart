import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

/// Turns a picked photo into the small product thumbnail that is stored on
/// the device and sent to the server (FR21).
///
/// Target: JPEG, longest side [maxDimension] px, quality [quality], under
/// [targetBytes] — a product photo is only ever shown as a small card / avatar.
/// JPEG rather than GIF: GIF is limited to 256 colours (banding, dithering on
/// photos) and is bigger than JPEG at this size. WebP would save ~25 % more but
/// the `image` package cannot encode it.
///
/// Two stages: the picker already downscales natively ([pickerMaxDimension],
/// [pickerQuality] — cheap, keeps memory low on 12 MP photos), then [compress]
/// re-encodes with the `image` package to guarantee the format, the size, the
/// EXIF orientation applied and every metadata block (GPS position…) dropped.
class ProductPhotoCompressor {
  const ProductPhotoCompressor();

  static const int maxDimension = 400;
  static const int quality = 70;
  static const int minQuality = 40;
  static const int targetBytes = 40 * 1024;

  /// `ImagePicker.pickImage` parameters (first, native stage).
  static const double pickerMaxDimension = 400;
  static const int pickerQuality = 70;

  /// Returns the JPEG thumbnail of [source] (any format the `image` package
  /// decodes: JPEG, PNG, WebP, HEIC is converted by the picker). Throws
  /// [FormatException] when [source] is not an image.
  Future<Uint8List> compress(Uint8List source) =>
      compute(compressSync, source, debugLabel: 'productPhotoCompress');

  /// Synchronous body of [compress] — runs in a background isolate.
  static Uint8List compressSync(Uint8List source) {
    final decoded = img.decodeImage(source);
    if (decoded == null) {
      throw const FormatException('Photo illisible');
    }
    // Apply the EXIF rotation to the pixels, then drop all metadata.
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
    var q = quality;
    var out = img.encodeJpg(image, quality: q);
    while (out.length > targetBytes && q > minQuality) {
      q -= 10;
      out = img.encodeJpg(image, quality: q);
    }
    return out;
  }
}
