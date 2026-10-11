import 'feedback_type.dart';

/// FeedbackSubmission — one feedback entry as typed by the user, plus the
/// context captured automatically (Story 14.5, FR92 — AC14/AC15).
class FeedbackSubmission {
  static const minDescriptionLength = 10;
  static const maxDescriptionLength = 500;

  final FeedbackType type;
  final String description;
  final String? appVersion;
  final String? platform;

  /// Route the user was on when they tapped "Signaler un problème"
  /// (`GoRouterState.uri.path`, never `.name` — routes are unnamed).
  final String? screenContext;

  /// Optional screenshot, JPEG, base64 (≤ 500 KB before encoding): the sync
  /// queue payload is a JSON text, no multipart endpoint exists.
  final String? screenshotBase64;

  const FeedbackSubmission({
    required this.type,
    required this.description,
    this.appVersion,
    this.platform,
    this.screenContext,
    this.screenshotBase64,
  });

  /// Payload of the `SUBMIT_FEEDBACK` operation — keys mirror
  /// `FeedbackController.SubmitFeedbackRequest` on the server.
  Map<String, dynamic> toPayload() => {
        'type': type.apiValue,
        'description': description,
        if (appVersion != null) 'appVersion': appVersion,
        if (platform != null) 'platform': platform,
        if (screenContext != null) 'screenContext': screenContext,
        if (screenshotBase64 != null) 'screenshotBase64': screenshotBase64,
      };
}
