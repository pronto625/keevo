import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/features/team/presentation/util/employee_whatsapp.dart';

void main() {
  group('buildInviteMessage (FR65)', () {
    test('contains first name, store and phone, never a password', () {
      final text = buildInviteMessage(
        firstName: 'Jean',
        storeName: 'Boutique A',
        phone: '+237690000001',
        downloadUrl: 'https://keevo.app/download',
      );
      expect(text, contains('Jean'));
      expect(text, contains('Boutique A'));
      expect(text, contains('+237690000001'));
      expect(text, contains('https://keevo.app/download'));
      expect(text, contains('séparément'));
      expect(text.toLowerCase(), isNot(contains('mot de passe :')));
    });

    test('blank download URL → « Installez l\'application Keevo » without link',
        () {
      final text = buildInviteMessage(
          firstName: 'Jean', storeName: 'A', phone: '+1', downloadUrl: '');
      expect(text, contains('Installez l\'application Keevo\n'));
      expect(text, isNot(contains('http')));
    });
  });

  test('buildReminderMessage mentions the 48 h and the store', () {
    final text = buildReminderMessage(firstName: 'Awa', storeName: 'Boutique A');
    expect(text, contains('Awa'));
    expect(text, contains('48 h'));
    expect(text, contains('Boutique A'));
  });

  test('whatsAppUri keeps digits only and encodes the text', () {
    final uri = whatsAppUri('+237 690-000-001', 'Bonjour & bienvenue');
    expect(uri.toString(), startsWith('https://wa.me/237690000001?text='));
    expect(uri.queryParameters['text'], 'Bonjour & bienvenue');
  });

  group('formatLastSeen', () {
    final now = DateTime(2026, 10, 14, 12);
    test('null → Aucune activité', () {
      expect(formatLastSeen(null, now), 'Aucune activité');
    });
    test('minutes, hours, days, date', () {
      expect(formatLastSeen(now.subtract(const Duration(seconds: 20)), now),
          'à l\'instant');
      expect(formatLastSeen(now.subtract(const Duration(minutes: 5)), now),
          'il y a 5 min');
      expect(formatLastSeen(now.subtract(const Duration(hours: 3)), now),
          'il y a 3 h');
      expect(formatLastSeen(now.subtract(const Duration(days: 2)), now),
          'il y a 2 j');
      expect(formatLastSeen(DateTime(2026, 9, 1, 10), now), 'le 01/09/2026');
    });
  });
}
