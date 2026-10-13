import '../model/tutorial.dart';

/// TutorialRepository — FR7 tutorial catalog (v1s-14-6).
abstract class TutorialRepository {
  /// Server list (then cached); on failure the cached list
  /// ([TutorialCatalog.fromCache] true); without cache, the error rethrows.
  Future<TutorialCatalog> getTutorials();
}
