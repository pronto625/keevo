import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/di/providers.dart';
import '../../../auth/presentation/provider/auth_provider.dart';
import '../../data/datasource/remote_tutorial_datasource.dart';
import '../../data/repository/tutorial_repository_impl.dart';
import '../../domain/model/tutorial.dart';
import '../../domain/repository/tutorial_repository.dart';

/// Providers of « Aide & Tutoriels » (FR7, v1s-14-6).

final tutorialRepositoryProvider = Provider<TutorialRepository>((ref) {
  return TutorialRepositoryImpl(
    remote: RemoteTutorialDataSource(dio: ref.watch(dioProvider)),
    prefs: ref.watch(sharedPreferencesProvider),
  );
});

final tutorialsProvider = FutureProvider.autoDispose<TutorialCatalog>((ref) {
  return ref.watch(tutorialRepositoryProvider).getTutorials();
});
