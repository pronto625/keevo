import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;

import 'package:keevo_core/photo/company_logo_compressor.dart';

void main() {
  test('transparent logo stays a PNG, longest side 600 px', () {
    final source = img.Image(width: 1200, height: 300, numChannels: 4);
    final out = CompanyLogoCompressor.compressSync(
        Uint8List.fromList(img.encodePng(source)));
    expect(out.png, isTrue);
    final decoded = img.decodePng(out.bytes)!;
    expect(decoded.width, 600);
    expect(decoded.height, 150);
    expect(out.bytes.length, lessThanOrEqualTo(CompanyLogoCompressor.targetBytes));
  });

  test('opaque photo becomes a JPEG under the size target', () {
    final source = img.Image(width: 900, height: 900);
    for (var y = 0; y < 900; y++) {
      for (var x = 0; x < 900; x++) {
        source.setPixelRgb(x, y, (x * 7) % 256, (y * 13) % 256, (x * y) % 256);
      }
    }
    final out = CompanyLogoCompressor.compressSync(
        Uint8List.fromList(img.encodeJpg(source, quality: 100)));
    expect(out.png, isFalse);
    expect(img.decodeJpg(out.bytes)!.width, 600);
    expect(out.bytes.length, lessThanOrEqualTo(CompanyLogoCompressor.targetBytes));
  });

  test('not an image → FormatException', () {
    expect(() => CompanyLogoCompressor.compressSync(Uint8List.fromList([1, 2, 3])),
        throwsFormatException);
  });
}
