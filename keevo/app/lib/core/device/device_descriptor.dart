import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:uuid/uuid.dart';

/// Signature of the device fields sent with a sign-in or a renewal.
typedef DeviceFields = Future<Map<String, String>> Function();

/// Story 16.11 — describes this device to the server at sign-in (tenant
/// selection) and on every session renewal, so « Appareils connectés » can
/// list it: the sync device id and a readable label (« Xiaomi Redmi Note 12 ·
/// Android 14 »). Never blocks a sign-in: any failure sends fewer fields.
class DeviceDescriptor {
  static const _channel = MethodChannel('keevo/device');

  final FlutterSecureStorage _storage;
  String? _label;

  DeviceDescriptor(this._storage);

  Future<Map<String, String>> fields() async {
    final fields = <String, String>{};
    try {
      var id = await _storage.read(key: kDeviceIdKey);
      if (id == null) {
        id = const Uuid().v4();
        await _storage.write(key: kDeviceIdKey, value: id);
      }
      fields['deviceId'] = id;
    } catch (_) {
      // Secure storage unavailable: the session simply has no device id.
    }
    final label = await this.label();
    if (label != null) fields['deviceLabel'] = label;
    return fields;
  }

  /// Model and system, cached for the process.
  Future<String?> label() async {
    final cached = _label;
    if (cached != null) return cached;
    String? label;
    try {
      label = await _channel.invokeMethod<String>('label');
    } catch (_) {
      label = null; // no platform channel (tests, other platforms)
    }
    label ??= _fallbackLabel();
    return _label = label;
  }

  static String? _fallbackLabel() {
    try {
      final os = Platform.operatingSystem;
      final name = os.isEmpty ? os : os[0].toUpperCase() + os.substring(1);
      return name.isEmpty ? null : name;
    } catch (_) {
      return null;
    }
  }
}
