import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/widget/contact_validation.dart';

void main() {
  group('normalizeContactPhone', () {
    test('local Cameroonian formats get +237', () {
      expect(normalizeContactPhone('6 99 11 22 33'), '+237699112233');
      expect(normalizeContactPhone('699-11-22-33'), '+237699112233');
      expect(normalizeContactPhone('222 33 44 55'), '+237222334455');
    });

    test('international formats are kept canonical', () {
      expect(normalizeContactPhone('+237 699 11 22 33'), '+237699112233');
      expect(normalizeContactPhone('00237699112233'), '+237699112233');
      expect(normalizeContactPhone('237699112233'), '+237699112233');
      expect(normalizeContactPhone('+33 6 12 34 56 78'), '+33612345678');
    });

    test('too short or unknown local numbers are refused', () {
      expect(normalizeContactPhone('12'), isNull);
      expect(normalizeContactPhone('712345678'), isNull);
      expect(normalizeContactPhone('+0123456789'), isNull);
      expect(normalizeContactPhone(''), isNull);
    });
  });

  test('French errors', () {
    expect(contactPhoneError(''), 'Le téléphone est requis');
    expect(contactPhoneError('12'), 'Numéro invalide. Exemple : 6 99 11 22 33');
    expect(contactPhoneError('699112233'), isNull);
    expect(contactEmailError(''), isNull);
    expect(contactEmailError('awa@'), 'Adresse e-mail invalide');
    expect(contactEmailError('awa@mail.cm'), isNull);
  });
}
