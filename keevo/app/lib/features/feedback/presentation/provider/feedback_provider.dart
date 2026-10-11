import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/sync/riverpod_sync_trigger_dispatcher.dart';
import '../../data/repository/feedback_repository_impl.dart';
import '../../domain/repository/feedback_repository.dart';

/// Providers for FR92 user feedback (Story 14.5).

final feedbackRepositoryProvider = Provider<FeedbackRepository>((ref) {
  return FeedbackRepositoryImpl(
    syncService: ref.watch(syncServiceProvider),
    syncTriggerDispatcher: ref.watch(syncTriggerDispatcherProvider),
  );
});

/// Version string sent with each feedback (`appVersion`, ≤ 20 chars).
///
/// Overridden in `main.dart` with `PackageInfo.version` (package_info_plus);
/// null when the platform cannot report it.
final appVersionProvider = Provider<String?>((_) => null);

/// Injectable so widget tests can stub the picker.
final imagePickerProvider = Provider<ImagePicker>((_) => ImagePicker());

/// `platform` value sent with each feedback — mirror of
/// `main_shell.dart._fcmPlatform()`.
String feedbackPlatform() {
  if (Platform.isAndroid) return 'ANDROID';
  if (Platform.isIOS) return 'IOS';
  if (Platform.isWindows) return 'WINDOWS';
  if (Platform.isMacOS) return 'MACOS';
  return 'LINUX';
}
