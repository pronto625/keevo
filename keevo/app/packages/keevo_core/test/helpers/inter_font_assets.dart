import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';

/// Story 15.4a — the Inter font files are bundled by the app
/// (`keevo/app/assets/fonts`), not by `keevo_core`. `AppTheme` resolves them
/// through `google_fonts` (runtime fetching off), which looks them up in the
/// asset manifest: package tests serve the app's files through a mock
/// `flutter/assets` channel. Call in `main()` before the first theme is built
/// (themes may be built while tests are declared); the mock is removed by a
/// `tearDownAll` once the pending font loads are done.
void mockAppFontAssets({String fontsDir = '../../assets/fonts'}) {
  final dir = Directory(fontsDir);
  if (!dir.existsSync()) {
    throw StateError('$fontsDir not found — run from packages/keevo_core');
  }
  final files = <String, File>{
    for (final f in dir.listSync().whereType<File>())
      if (f.path.endsWith('.ttf')) 'assets/fonts/${f.uri.pathSegments.last}': f,
  };
  final manifest = <String, Object>{
    for (final key in files.keys)
      key: [
        {'asset': key},
      ],
  };
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDownAll(() async {
    // Font loads started by the tests are async: let them finish first.
    await GoogleFonts.pendingFonts();
    messenger.setMockMessageHandler('flutter/assets', null);
  });
  messenger.setMockMessageHandler('flutter/assets', (message) async {
    final key = Uri.decodeFull(utf8.decode(message!.buffer
        .asUint8List(message.offsetInBytes, message.lengthInBytes)));
    if (key == 'AssetManifest.bin') {
      return const StandardMessageCodec().encodeMessage(manifest);
    }
    final file = files[key];
    return file == null ? null : ByteData.sublistView(file.readAsBytesSync());
  });
}
