import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;

import 'package:keevo/features/feedback/data/service/screenshot_compressor.dart';

/// Random noise is the worst case for JPEG: it does not compress, so a large
/// noisy PNG is a faithful stand-in for a heavy screenshot.
Uint8List _noisePng(int width, int height) {
  final rng = Random(42);
  final image = img.Image(width: width, height: height);
  for (var y = 0; y < height; y++) {
    for (var x = 0; x < width; x++) {
      image.setPixelRgb(
          x, y, rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
    }
  }
  return img.encodePng(image);
}

void main() {
  group('ScreenshotCompressor (Story 14.5 — AC14)', () {
    test('a heavy image ends up as a JPEG of at most 500 KB', () {
      final input = _noisePng(1600, 1200);
      expect(input.length, greaterThan(ScreenshotCompressor.defaultMaxBytes));

      final output = ScreenshotCompressor.compressSync(input);

      expect(output.length, lessThanOrEqualTo(ScreenshotCompressor.defaultMaxBytes));
      final decoded = img.decodeJpg(output);
      expect(decoded, isNotNull);
      expect(max(decoded!.width, decoded.height), lessThanOrEqualTo(1280));
    });

    test('a small image keeps its dimensions', () {
      final input = _noisePng(320, 200);

      final output = ScreenshotCompressor.compressSync(input);

      final decoded = img.decodeJpg(output)!;
      expect(decoded.width, 320);
      expect(decoded.height, 200);
    });

    test('a tighter limit downscales further', () {
      final input = _noisePng(800, 800);

      final output =
          ScreenshotCompressor.compressSync(input, maxBytes: 60 * 1024);

      expect(output.length, lessThanOrEqualTo(60 * 1024));
      expect(img.decodeJpg(output)!.width, lessThan(800));
    });

    test('non-image bytes throw a FormatException', () {
      expect(
        () => ScreenshotCompressor.compressSync(Uint8List.fromList([1, 2, 3])),
        throwsFormatException,
      );
    });
  });
}
