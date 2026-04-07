import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:keevo/features/dashboard/domain/model/motivational_message_service.dart';

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('MotivationalMessageService', () {
    test('returns message containing firstName', () {
      final msg = MotivationalMessageService.getMessage(
        firstName: 'Amadou',
        yesterdayCA: 50000,
        now: DateTime(2025, 3, 15),
      );
      expect(msg, contains('Amadou'));
    });

    test('replaces {amount} with formatted yesterday CA', () {
      final msg = MotivationalMessageService.getMessage(
        firstName: 'Test',
        yesterdayCA: 125000,
        now: DateTime(2025, 3, 15),
      );
      expect(msg, contains('125\u202f000'));
    });

    test('uses zero-CA template when yesterdayCA is 0', () {
      final msg = MotivationalMessageService.getMessage(
        firstName: 'Fatou',
        yesterdayCA: 0,
        now: DateTime(2025, 3, 15),
      );
      expect(msg, contains('Fatou'));
      // Zero-CA templates don't contain {amount}
      expect(msg.contains('FCFA'), isFalse);
    });

    test('same day produces same message (deterministic)', () {
      final msg1 = MotivationalMessageService.getMessage(
        firstName: 'Issa',
        yesterdayCA: 30000,
        now: DateTime(2025, 6, 10),
      );
      final msg2 = MotivationalMessageService.getMessage(
        firstName: 'Issa',
        yesterdayCA: 30000,
        now: DateTime(2025, 6, 10),
      );
      expect(msg1, msg2);
    });

    test('different day can produce different message', () {
      final msg1 = MotivationalMessageService.getMessage(
        firstName: 'Issa',
        yesterdayCA: 30000,
        now: DateTime(2025, 1, 1), // day 0
      );
      final msg2 = MotivationalMessageService.getMessage(
        firstName: 'Issa',
        yesterdayCA: 30000,
        now: DateTime(2025, 1, 2), // day 1
      );
      expect(msg1, isNot(msg2));
    });

    test('amount formatting adds space separator for thousands', () {
      final msg = MotivationalMessageService.getMessage(
        firstName: 'A',
        yesterdayCA: 1500000,
        now: DateTime(2025, 1, 1),
      );
      expect(msg, contains('1\u202f500\u202f000'));
    });
  });
}
