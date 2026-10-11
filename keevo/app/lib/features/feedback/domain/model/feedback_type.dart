import 'package:flutter/material.dart';

/// FeedbackType — what the user wants to tell Toor (Story 14.5, FR92 — AC14).
///
/// [apiValue] is the `type` string stored by `POST /api/v1/feedback` /
/// the `SUBMIT_FEEDBACK` sync handler (free text on the server, max 30).
enum FeedbackType {
  suggestion('SUGGESTION', 'Suggestion', Icons.lightbulb_outline_rounded),
  bug('BUG', 'Signaler un problème', Icons.bug_report_outlined),
  praise('PRAISE', "J'adore Keevo", Icons.favorite_outline_rounded);

  const FeedbackType(this.apiValue, this.label, this.icon);

  final String apiValue;
  final String label;
  final IconData icon;
}
