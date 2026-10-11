import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

/// ScreenshotCompressor — shrinks a picked screenshot to a JPEG of at most
/// [maxBytes] (Story 14.5, AC14: "compression pour viser ≤500KB").
///
/// Strategy: decode, cap the longest side, then re-encode at decreasing
/// JPEG quality; if still too large, downscale by 25% and try again. Runs
/// off the UI thread through [compute]. Throws [FormatException] when the
/// bytes are not an image.
class ScreenshotCompressor {
  static const defaultMaxBytes = 500 * 1024;

  const ScreenshotCompressor._();

  static Future<Uint8List> compress(
    Uint8List bytes, {
    int maxBytes = defaultMaxBytes,
  }) {
    return compute(
      _compressSync,
      _CompressRequest(bytes: bytes, maxBytes: maxBytes),
      debugLabel: 'ScreenshotCompressor',
    );
  }

  /// Synchronous core, public for direct use in unit tests.
  @visibleForTesting
  static Uint8List compressSync(Uint8List bytes,
          {int maxBytes = defaultMaxBytes}) =>
      _compressSync(_CompressRequest(bytes: bytes, maxBytes: maxBytes));
}

class _CompressRequest {
  final Uint8List bytes;
  final int maxBytes;
  const _CompressRequest({required this.bytes, required this.maxBytes});
}

const _qualities = [85, 70, 55, 40];
const _initialMaxSide = 1280;
const _minSide = 240;

Uint8List _compressSync(_CompressRequest request) {
  img.Image? decoded;
  try {
    decoded = img.decodeImage(request.bytes);
  } catch (_) {
    // Truncated or garbage bytes make the decoders throw RangeError & co.
    decoded = null;
  }
  if (decoded == null) {
    throw const FormatException('Image non reconnue');
  }

  var image = _capLongestSide(decoded, _initialMaxSide);
  Uint8List? best;
  while (true) {
    for (final quality in _qualities) {
      final encoded = img.encodeJpg(image, quality: quality);
      best = encoded;
      if (encoded.length <= request.maxBytes) return encoded;
    }
    final longest = image.width > image.height ? image.width : image.height;
    if (longest <= _minSide) break;
    image = _capLongestSide(image, (longest * 0.75).round());
  }
  // Pathological input: return the smallest attempt rather than fail.
  return best!;
}

img.Image _capLongestSide(img.Image image, int maxSide) {
  final longest = image.width > image.height ? image.width : image.height;
  if (longest <= maxSide) return image;
  return image.width >= image.height
      ? img.copyResize(image, width: maxSide)
      : img.copyResize(image, height: maxSide);
}
