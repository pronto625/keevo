import 'package:intl/intl.dart';

/// MotivationalMessageService — Template Method pattern for daily motivational messages.
///
/// Story 7.1 — AC5: personalized, daily rotation, dismissible.
/// Uses dayOfYear % templates.length for deterministic daily rotation.
class MotivationalMessageService {
  MotivationalMessageService._();

  static const List<String> _templates = [
    '💪 Bonne journée {name} ! Hier vous avez fait {amount} FCFA. Visez plus haut aujourd\'hui !',
    '🌟 {name}, prêt(e) à dépasser les {amount} FCFA d\'hier ? C\'est parti !',
    '🚀 Nouvelle journée, nouvelles ventes ! Hier : {amount} FCFA. Aujourd\'hui : encore mieux !',
    '☀️ Bonjour {name} ! Le soleil brille et votre business aussi — {amount} FCFA hier !',
    '🎯 Objectif du jour : battre les {amount} FCFA d\'hier. Vous pouvez le faire {name} !',
    '💰 {amount} FCFA hier, combien aujourd\'hui ? À vous de jouer {name} !',
    '📈 Votre business grandit ! {amount} FCFA hier. Continuez comme ça {name} !',
    '⭐ Chaque vente compte ! Hier : {amount} FCFA. Aujourd\'hui sera meilleur {name} !',
    '🏆 Champion(ne) ! {amount} FCFA hier. Le record est à portée de main !',
    '🔥 {name}, hier c\'était {amount} FCFA. Aujourd\'hui on fait exploser le compteur !',
  ];

  static const List<String> _zeroCATemplates = [
    '🌟 Nouvelle journée, nouvelles opportunités ! C\'est parti {name} !',
    '☀️ Bonjour {name} ! Que cette journée soit pleine de bonnes ventes !',
    '💪 {name}, aujourd\'hui est un nouveau départ. Chaque vente compte !',
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
