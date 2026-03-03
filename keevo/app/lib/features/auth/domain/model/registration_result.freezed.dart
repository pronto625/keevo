// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'registration_result.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
    'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models');

/// @nodoc
mixin _$RegistrationResult {
  String get tenantCode => throw _privateConstructorUsedError;
  String get token => throw _privateConstructorUsedError;
  String get userId => throw _privateConstructorUsedError;
  String get tenantId => throw _privateConstructorUsedError;

  /// Create a copy of RegistrationResult
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $RegistrationResultCopyWith<RegistrationResult> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $RegistrationResultCopyWith<$Res> {
  factory $RegistrationResultCopyWith(
          RegistrationResult value, $Res Function(RegistrationResult) then) =
      _$RegistrationResultCopyWithImpl<$Res, RegistrationResult>;
  @useResult
  $Res call({String tenantCode, String token, String userId, String tenantId});
}

/// @nodoc
class _$RegistrationResultCopyWithImpl<$Res, $Val extends RegistrationResult>
    implements $RegistrationResultCopyWith<$Res> {
  _$RegistrationResultCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of RegistrationResult
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenantCode = null,
    Object? token = null,
    Object? userId = null,
    Object? tenantId = null,
  }) {
    return _then(_value.copyWith(
      tenantCode: null == tenantCode
          ? _value.tenantCode
          : tenantCode // ignore: cast_nullable_to_non_nullable
              as String,
      token: null == token
          ? _value.token
          : token // ignore: cast_nullable_to_non_nullable
              as String,
      userId: null == userId
          ? _value.userId
          : userId // ignore: cast_nullable_to_non_nullable
              as String,
      tenantId: null == tenantId
          ? _value.tenantId
          : tenantId // ignore: cast_nullable_to_non_nullable
              as String,
    ) as $Val);
  }
}

/// @nodoc
abstract class _$$RegistrationResultImplCopyWith<$Res>
    implements $RegistrationResultCopyWith<$Res> {
  factory _$$RegistrationResultImplCopyWith(_$RegistrationResultImpl value,
          $Res Function(_$RegistrationResultImpl) then) =
      __$$RegistrationResultImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String tenantCode, String token, String userId, String tenantId});
}

/// @nodoc
class __$$RegistrationResultImplCopyWithImpl<$Res>
    extends _$RegistrationResultCopyWithImpl<$Res, _$RegistrationResultImpl>
    implements _$$RegistrationResultImplCopyWith<$Res> {
  __$$RegistrationResultImplCopyWithImpl(_$RegistrationResultImpl _value,
      $Res Function(_$RegistrationResultImpl) _then)
      : super(_value, _then);

  /// Create a copy of RegistrationResult
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenantCode = null,
    Object? token = null,
    Object? userId = null,
    Object? tenantId = null,
  }) {
    return _then(_$RegistrationResultImpl(
      tenantCode: null == tenantCode
          ? _value.tenantCode
          : tenantCode // ignore: cast_nullable_to_non_nullable
              as String,
      token: null == token
          ? _value.token
          : token // ignore: cast_nullable_to_non_nullable
              as String,
      userId: null == userId
          ? _value.userId
          : userId // ignore: cast_nullable_to_non_nullable
              as String,
      tenantId: null == tenantId
          ? _value.tenantId
          : tenantId // ignore: cast_nullable_to_non_nullable
              as String,
    ));
  }
}

/// @nodoc

class _$RegistrationResultImpl implements _RegistrationResult {
  const _$RegistrationResultImpl(
      {required this.tenantCode,
      required this.token,
      required this.userId,
      required this.tenantId});

  @override
  final String tenantCode;
  @override
  final String token;
  @override
  final String userId;
  @override
  final String tenantId;

  @override
  String toString() {
    return 'RegistrationResult(tenantCode: $tenantCode, token: $token, userId: $userId, tenantId: $tenantId)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$RegistrationResultImpl &&
            (identical(other.tenantCode, tenantCode) ||
                other.tenantCode == tenantCode) &&
            (identical(other.token, token) || other.token == token) &&
            (identical(other.userId, userId) || other.userId == userId) &&
            (identical(other.tenantId, tenantId) ||
                other.tenantId == tenantId));
  }

  @override
  int get hashCode =>
      Object.hash(runtimeType, tenantCode, token, userId, tenantId);

  /// Create a copy of RegistrationResult
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$RegistrationResultImplCopyWith<_$RegistrationResultImpl> get copyWith =>
      __$$RegistrationResultImplCopyWithImpl<_$RegistrationResultImpl>(
          this, _$identity);
}

abstract class _RegistrationResult implements RegistrationResult {
  const factory _RegistrationResult(
      {required final String tenantCode,
      required final String token,
      required final String userId,
      required final String tenantId}) = _$RegistrationResultImpl;

  @override
  String get tenantCode;
  @override
  String get token;
  @override
  String get userId;
  @override
  String get tenantId;

  /// Create a copy of RegistrationResult
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$RegistrationResultImplCopyWith<_$RegistrationResultImpl> get copyWith =>
      throw _privateConstructorUsedError;
}
