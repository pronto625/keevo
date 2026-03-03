// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'auth_provider.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

String _$registrationHash() => r'bb2bc141ca0f03bc8b5293667601be2fec1b9cf6';

/// [Registration] manages the async registration lifecycle.
///
/// State: [AsyncValue<RegistrationResult?>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Success: AsyncData(RegistrationResult(...))
/// - Error: AsyncError(exception, stackTrace)
///
/// Copied from [Registration].
@ProviderFor(Registration)
final registrationProvider = AutoDisposeAsyncNotifierProvider<Registration,
    RegistrationResult?>.internal(
  Registration.new,
  name: r'registrationProvider',
  debugGetCreateSourceHash:
      const bool.fromEnvironment('dart.vm.product') ? null : _$registrationHash,
  dependencies: null,
  allTransitiveDependencies: null,
);

typedef _$Registration = AutoDisposeAsyncNotifier<RegistrationResult?>;
// ignore_for_file: type=lint
// ignore_for_file: subtype_of_sealed_class, invalid_use_of_internal_member, invalid_use_of_visible_for_testing_member, deprecated_member_use_from_same_package
