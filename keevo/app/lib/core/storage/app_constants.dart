// Application-wide SharedPreferences keys.
//
// Centralised here so that [app_router.dart] and feature pages
// all reference the exact same strings without cross-importing each other.

// Set to `true` after the user has seen the onboarding slides.
const String kOnboardingSeenKey = 'onboarding_seen';

// Set to `true` after the user has accepted the Terms of Service.
const String kTermsAcceptedKey = 'terms_accepted';
