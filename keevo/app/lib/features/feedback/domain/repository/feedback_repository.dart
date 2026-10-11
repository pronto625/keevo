import '../model/feedback_submission.dart';

/// FeedbackRepository — FR92 (Story 14.5).
abstract interface class FeedbackRepository {
  /// Queues the feedback for sync (works offline) and triggers a background
  /// push. Returns the locally generated feedback id.
  Future<String> submit(FeedbackSubmission submission);
}
