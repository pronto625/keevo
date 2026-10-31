import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:image_picker/image_picker.dart';
import 'package:keevo/core/photo/product_photo_providers.dart';
import 'package:keevo/features/settings/data/datasource/company_profile_api.dart';
import 'package:keevo/features/settings/domain/model/company_profile.dart';
import 'package:keevo/features/settings/presentation/page/company_page.dart';
import 'package:keevo/features/settings/presentation/provider/company_profile_provider.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/photo/company_logo_compressor.dart';
import 'package:keevo_core/sync/connectivity_service.dart';

class _Connectivity implements ConnectivityService {
  _Connectivity(this.online);
  bool online;

  @override
  Future<bool> isOnline() async => online;

  @override
  Stream<bool> get onlineStream => const Stream.empty();
}

/// In-memory server: designation, shop name, logo.
class _FakeApi extends CompanyProfileApi {
  _FakeApi({this.designation, this.logo, this.failLoad = false})
      : super(Dio());

  String? designation;
  Uint8List? logo;
  bool failLoad;
  final String shopName = 'Boutique Awa';
  final List<String> calls = [];

  CompanyProfile get _profile => CompanyProfile(
        designation: designation,
        displayName: designation ?? shopName,
        shopName: shopName,
        hasLogo: logo != null,
      );

  @override
  Future<CompanyProfile> fetch() async {
    calls.add('fetch');
    if (failLoad) {
      throw DioException(
        requestOptions: RequestOptions(path: '/api/v1/account/company'),
        response: Response(
          requestOptions: RequestOptions(path: '/api/v1/account/company'),
          statusCode: 503,
          data: {'error': 'Service momentanément indisponible, réessayez'},
        ),
        type: DioExceptionType.badResponse,
      );
    }
    return _profile;
  }

  @override
  Future<CompanyProfile> updateDesignation(String? value) async {
    calls.add('put:${value ?? ''}');
    final v = value?.trim() ?? '';
    designation = v.isEmpty ? null : v;
    return _profile;
  }

  @override
  Future<Uint8List?> fetchLogo() async => logo;

  @override
  Future<CompanyProfile> uploadLogo(Uint8List bytes, {required bool png}) async {
    calls.add('logo:${png ? 'png' : 'jpeg'}');
    logo = bytes;
    return _profile;
  }

  @override
  Future<CompanyProfile> deleteLogo() async {
    calls.add('deleteLogo');
    logo = null;
    return _profile;
  }
}

class _FakePicker extends ImagePicker {
  _FakePicker(this.bytes, {this.fail = false});
  final Uint8List bytes;
  final bool fail;

  @override
  Future<XFile?> pickImage({
    required ImageSource source,
    double? maxWidth,
    double? maxHeight,
    int? imageQuality,
    CameraDevice preferredCameraDevice = CameraDevice.rear,
    bool requestFullMetadata = true,
  }) async =>
      fail ? throw Exception('no gallery') : XFile.fromData(bytes, name: 'logo.png');
}

/// Synchronous compression (no isolate in widget tests).
class _SyncCompressor extends CompanyLogoCompressor {
  const _SyncCompressor();

  @override
  Future<CompressedLogo> compress(Uint8List source) async =>
      CompanyLogoCompressor.compressSync(source);
}

Uint8List _png() {
  final image = img.Image(width: 40, height: 20, numChannels: 4);
  return Uint8List.fromList(img.encodePng(image));
}

Widget _app(_FakeApi api, _Connectivity connectivity, {bool pickerFails = false}) {
  return ProviderScope(
    overrides: [
      companyProfileApiProvider.overrideWithValue(api),
      connectivityServiceProvider.overrideWithValue(connectivity),
      productPhotoPickerProvider.overrideWithValue(_FakePicker(_png(), fail: pickerFails)),
      companyLogoCompressorProvider.overrideWithValue(const _SyncCompressor()),
    ],
    child: const MaterialApp(home: CompanyPage()),
  );
}

void main() {
  testWidgets('offline: French message, nothing loaded', (tester) async {
    final api = _FakeApi();
    await tester.pumpWidget(_app(api, _Connectivity(false)));
    await tester.pumpAndSettle();
    expect(find.text(
        'Hors ligne — l\'identité de l\'entreprise se modifie avec une connexion internet.'),
        findsOneWidget);
    expect(api.calls, isEmpty);
    expect(find.byKey(const Key('company-designation')), findsNothing);
  });

  testWidgets('designation: preview follows the typing, save sends it, blank '
      '→ shop name', (tester) async {
    final api = _FakeApi();
    await tester.pumpWidget(_app(api, _Connectivity(true)));
    await tester.pumpAndSettle();
    expect(find.text('Nom affiché : Boutique Awa'), findsOneWidget);
    // nothing changed → save disabled
    expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('company-save')))
            .onPressed,
        isNull);

    await tester.enterText(
        find.byKey(const Key('company-designation')), 'Tech Centre SARL');
    await tester.pump();
    expect(find.text('Nom affiché : Tech Centre SARL'), findsOneWidget);
    await tester.tap(find.byKey(const Key('company-save')));
    await tester.pumpAndSettle();
    expect(api.calls, contains('put:Tech Centre SARL'));
    expect(api.designation, 'Tech Centre SARL');
    expect(find.text('Identité de l\'entreprise enregistrée'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('company-designation')), '');
    await tester.pump();
    expect(find.text('Nom affiché : Boutique Awa'), findsOneWidget);
    await tester.tap(find.byKey(const Key('company-save')));
    await tester.pumpAndSettle();
    expect(api.designation, isNull);
  });

  testWidgets('logo: pick → compressed PNG uploaded and shown; remove after '
      'confirmation', (tester) async {
    final api = _FakeApi();
    await tester.pumpWidget(_app(api, _Connectivity(true)));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('company-logo-empty')), findsOneWidget);
    expect(find.text('Choisir un logo'), findsOneWidget);

    await tester.tap(find.byKey(const Key('company-logo-pick')));
    await tester.pumpAndSettle();
    expect(api.calls, contains('logo:png'));
    expect(find.byKey(const Key('company-logo-image')), findsOneWidget);
    expect(find.text('Remplacer'), findsOneWidget);

    await tester.tap(find.byKey(const Key('company-logo-remove')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('company-logo-delete-confirm')));
    await tester.pumpAndSettle();
    expect(api.calls, contains('deleteLogo'));
    expect(find.byKey(const Key('company-logo-empty')), findsOneWidget);
  });

  testWidgets('existing designation and logo are shown; clearing it previews '
      'the shop name from the server', (tester) async {
    final api = _FakeApi(designation: 'Tech Centre SARL', logo: _png());
    await tester.pumpWidget(_app(api, _Connectivity(true)));
    await tester.pumpAndSettle();
    expect(find.text('Nom affiché : Tech Centre SARL'), findsOneWidget);
    expect(find.byKey(const Key('company-logo-image')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('company-designation')), '');
    await tester.pump();
    expect(find.text('Nom affiché : Boutique Awa'), findsOneWidget);
  });

  testWidgets('gallery failure: French snackbar, nothing uploaded',
      (tester) async {
    final api = _FakeApi();
    await tester.pumpWidget(_app(api, _Connectivity(true), pickerFails: true));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('company-logo-pick')));
    await tester.pumpAndSettle();
    expect(find.text('Impossible d\'ouvrir la galerie'), findsOneWidget);
    expect(api.calls.where((c) => c.startsWith('logo:')), isEmpty);
  });

  testWidgets('load failure: French message and retry', (tester) async {
    final api = _FakeApi(failLoad: true);
    await tester.pumpWidget(_app(api, _Connectivity(true)));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('company-load-message')), findsOneWidget);
    expect(find.textContaining('Exception'), findsNothing);
    api.failLoad = false;
    await tester.tap(find.byKey(const Key('company-retry')));
    await tester.pumpAndSettle();
    expect(find.text('Nom affiché : Boutique Awa'), findsOneWidget);
  });
}
