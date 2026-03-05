// Application-wide SharedPreferences keys.
//
// Centralised here so that [app_router.dart] and feature pages
// all reference the exact same strings without cross-importing each other.

// Set to `true` after the user has seen the onboarding slides.
const String kOnboardingSeenKey = 'onboarding_seen';

// Set to `true` after the user has accepted the Terms of Service.
const String kTermsAcceptedKey = 'terms_accepted';

// Set to `true` after the user has completed the onboarding wizard
// (sector selection + shop name). Used by the splash redirect to route
// a newly registered user through the wizard before reaching the POS.
const String kOnboardingWizardSeenKey = 'onboarding_wizard_seen';

// Set to `true` after the POS tutorial SnackBar has been shown once.
const String kPosTutorialShownKey = 'pos_tutorial_shown';

// Stores the selected sector apiCode (e.g. 'CLOTHING') persisted after wizard
// completion. Used by [PosPlaceholderPage] to show the correct sector emoji
// in the empty state (AC6).
const String kSectorTypeKey = 'sector_type';
