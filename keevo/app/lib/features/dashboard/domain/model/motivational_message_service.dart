import 'package:intl/intl.dart';

/// MotivationalMessageService — Template Method pattern for daily motivational messages.
///
/// Story 7.1 — AC5: personalized, daily rotation, dismissible.
/// Uses dayOfYear % templates.length for deterministic daily rotation.
///
/// J14 — the card stays visible all day, under a time-aware greeting
/// (« Bonsoir … » in the evening): no template greets by time of day
/// (no « Bonjour », « Bonne journée », « le soleil brille »).
class MotivationalMessageService {
  MotivationalMessageService._();

  static const List<String> _templates = [
    'Allez {name} ! Hier vous avez fait {amount} XAF. Visez plus haut aujourd\'hui !',
    '{name}, prêt(e) à dépasser les {amount} XAF d\'hier ? C\'est parti !',
    'Nouvelle journée, nouvelles ventes ! Hier : {amount} XAF. Aujourd\'hui : encore mieux !',
    '{name}, votre business brille — {amount} XAF hier !',
    'Objectif du jour : battre les {amount} XAF d\'hier. Vous pouvez le faire {name} !',
    '{amount} XAF hier, combien aujourd\'hui ? À vous de jouer {name} !',
    'Votre business grandit ! {amount} XAF hier. Continuez comme ça {name} !',
    'Chaque vente compte ! Hier : {amount} XAF. Aujourd\'hui sera meilleur {name} !',
    'Champion(ne) ! {amount} XAF hier. Le record est à portée de main !',
    '{name}, hier c\'était {amount} XAF. Aujourd\'hui on fait exploser le compteur !',
  ];

  static const List<String> _zeroCATemplates = [
    'Nouvelle journée, nouvelles opportunités ! C\'est parti {name} !',
    '{name}, que vos ventes soient nombreuses aujourd\'hui !',
    '{name}, aujourd\'hui est un nouveau départ. Chaque vente compte !',
  ];

  /// Returns the motivational message for today, personalized with name and amount.
  ///
  /// Uses [dayOfYear] % templates.length for deterministic daily rotation.
  /// Same message all day even if app is reopened.
  static String getMessage({
    required String firstName,
    required int yesterdayCA,
    DateTime? now,
  }) {
    final today = now ?? DateTime.now();
    final dayOfYear =
        today.difference(DateTime(today.year, 1, 1)).inDays;

    if (yesterdayCA == 0) {
      final index = dayOfYear % _zeroCATemplates.length;
      return _zeroCATemplates[index].replaceAll('{name}', firstName);
    }

    final index = dayOfYear % _templates.length;
    final amountFormatted = _formatAmount(yesterdayCA);
    return _templates[index]
        .replaceAll('{name}', firstName)
        .replaceAll('{amount}', amountFormatted);
  }

  /// Format amount with thousands separator (e.g., 125 000).
  static String _formatAmount(int amount) {
    return NumberFormat.decimalPattern('fr_FR').format(amount);
  }
}
