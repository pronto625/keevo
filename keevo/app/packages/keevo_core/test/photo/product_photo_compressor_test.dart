import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:keevo_core/photo/product_photo_compressor.dart';

/// A camera-like photo: smooth gradients plus sensor noise (noise is what
/// makes real photos heavy in JPEG), with an EXIF block.
Uint8List _photo(int w, int h, {bool withExif = true}) {
  final rnd = Random(42);
  final image = img.Image(width: w, height: h);
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      final n = rnd.nextInt(40) - 20;
      image.setPixelRgb(
        x,
        y,
        (x * 255 ~/ w + n).clamp(0, 255),
        (y * 255 ~/ h + n).clamp(0, 255),
        ((x + y) * 128 ~/ (w + h) + 60 + n).clamp(0, 255),
      );
    }
  }
  if (withExif) {
    image.exif.imageIfd['Make'] = img.IfdValueAscii('SECRET-PHONE-MAKER');
  }
  return img.encodeJpg(image, quality: 95);
}

void main() {
  test('picker parameters: native downscale to 400 px, quality 70', () {
    expect(ProductPhotoCompressor.pickerMaxDimension, 400);
    expect(ProductPhotoCompressor.pickerQuality, 70);
    expect(ProductPhotoCompressor.maxDimension, 400);
    expect(ProductPhotoCompressor.quality, 70);
  });

  test(
      'a 1600×1200 photo becomes a JPEG of 400×300, under 40 KB, '
      'without EXIF', () async {
    final source = _photo(1600, 1200);

    final out = await const ProductPhotoCompressor().compress(source);

    final decoded = img.decodeJpg(out)!;
    expect(decoded.width, 400);
    expect(decoded.height, 300);
    expect(out.length, lessThan(ProductPhotoCompressor.targetBytes));
    expect(out.length, lessThan(source.length));
    expect(String.fromCharCodes(out).contains('SECRET-PHONE-MAKER'), isFalse);
    expect(out[0], 0xFF);
    expect(out[1], 0xD8);
    // Visible in the test log: the size reached on this sample.
    // ignore: avoid_print
    print('sample 1600×1200 (${source.length} B) → ${out.length} B');
  });

  test('a portrait photo is capped on its height', () {
    final out = ProductPhotoCompressor.compressSync(_photo(600, 900));

    final decoded = img.decodeJpg(out)!;
    expect(decoded.height, 400);
    expect(decoded.width, 267);
  });

  test('a small image is not upscaled', () {
    final out = ProductPhotoCompressor.compressSync(_photo(200, 150));

    final decoded = img.decodeJpg(out)!;
    expect(decoded.width, 200);
    expect(decoded.height, 150);
  });

  test('a PNG is accepted and becomes a JPEG', () {
    final png = img.encodePng(img.Image(width: 500, height: 500));

    final out = ProductPhotoCompressor.compressSync(png);

    expect(img.decodeJpg(out)!.width, 400);
  });

  test('not an image → FormatException', () {
    expect(
      () => ProductPhotoCompressor.compressSync(
          Uint8List.fromList('pas une image'.codeUnits)),
      throwsA(isA<FormatException>()),
    );
  });
}
