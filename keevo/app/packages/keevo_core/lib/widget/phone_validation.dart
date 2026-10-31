import 'package:intl_phone_field/phone_number.dart';

/// Digits only and a length valid for the selected country. IntlPhoneField
/// calls onChanged on every keystroke, so "a change happened" is not "valid".
bool isValidPhoneNumber(PhoneNumber? phone) {
  if (phone == null || !RegExp(r'^\d+$').hasMatch(phone.number)) return false;
  try {
    return phone.isValidNumber();
  } catch (_) {
    return false;
  }
}
