import 'package:flutter/foundation.dart';

/// Default backend URL for local development (Genymotion / `adb reverse`).
const kDevApiBaseUrl = 'http://localhost:4500';

/// Raw `--dart-define=API_BASE_URL=…` value, empty when not provided.
const _definedApiBaseUrl = String.fromEnvironment('API_BASE_URL');

/// Thrown at startup by a release build that has no usable backend URL.
class InvalidApiBaseUrlError extends Error {
  final String message;
  InvalidApiBaseUrlError(this.message);

  @override
  String toString() => 'InvalidApiBaseUrlError: $message';
}

/// Resolves the backend base URL.
///
/// Debug / profile: [defined] when given, else [kDevApiBaseUrl] (local dev,
/// `adb reverse`). Release: [defined] is mandatory and must be an `https://`
/// URL with a host — a release built without
/// `--dart-define=API_BASE_URL=https://…` would otherwise ship pointing at
/// localhost, in clear text, with no warning.
String resolveApiBaseUrl(String defined, {required bool isRelease}) {
  final url = defined.trim();
  if (!isRelease) return url.isEmpty ? kDevApiBaseUrl : url;
  if (url.isEmpty) {
    throw InvalidApiBaseUrlError(
      'API_BASE_URL manquant pour un build release. Construire avec '
      '--dart-define=API_BASE_URL=https://<domaine-api>.',
    );
  }
  final uri = Uri.tryParse(url);
  if (uri == null || uri.scheme != 'https' || uri.host.isEmpty) {
    throw InvalidApiBaseUrlError(
      'API_BASE_URL doit être une URL https:// en release (reçu : "$url").',
    );
  }
  return url;
}

/// Backend base URL of this build — see [resolveApiBaseUrl].
final String apiBaseUrl =
    resolveApiBaseUrl(_definedApiBaseUrl, isRelease: kReleaseMode);
