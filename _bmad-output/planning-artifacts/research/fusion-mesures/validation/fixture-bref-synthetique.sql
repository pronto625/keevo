-- Fixture SYNTHÉTIQUE pour valider le kit 18.1 sur un schéma Bref reconstruit (typeorm schema:sync, branche develop).
-- À charger UNIQUEMENT dans un Postgres jetable (validate-local.sh). Jamais sur un réplica ni une base réelle.
-- Tous les numéros, noms et emails sont inventés (le +237 6 99 01 77 17 est l exemple de la matrice de la spec).
--
-- Attendus (voir validate-local.sh) :
--   Q1.c payants en vigueur = 2 (A : pro / mycoolpay ; F : team / apple en grâce) — B expiré non compté
--   Q1.b PROXY 1 = 1 (A), PROXY 2 = 2 (A, E), PROXY 3 = 1 (A via son membre D)
--   Q3   owners sans téléphone = 1 (C, google) ; membres sans téléphone = 1 (D, username seul) ; superadmin = 1 (S, hors membres)
--   Q3.e owners : 1 vide après normalisation (H) ; membres (seller) : 1 sans préfixe connu (G)
--   Q5.b boutiques hors CM = 1 (E1, SN) ; Q5.e décimales : invoice.subtotal 1, invoice.total 1, item.unitPrice 1, item.total 1
--   Q5.c CM : 1 conforme + 1 « TVA ignorée » (I4, tax 0 net > 0) ; I5 (entièrement remisé) conforme, pas « TVA ignorée »
--   Q7.a « avant develop » : 2 ventes (I2, I5) dont 1 total incohérent (I2) ; « jour de la bascule » = 1 document (I6), 0 incohérent
--   Q8.b = 1 (B : pro, expired, momo, expiré < 30 j)
--   Q4   owners exportés = 5 (A, B, E, F ; H exclu car vide ; G membre à part), membres = 1 (G, sans préfixe connu)
BEGIN;
INSERT INTO "user" (id, email, phone, role, plan, provider, "firstName", "lastName") VALUES
  ('11111111-1111-1111-1111-111111111111', 'fixture-a@example.test', '+237 6 99 01 77 17', 'owner', 'pro', 'email', 'Fixture', 'A'),
  ('22222222-2222-2222-2222-222222222222', 'fixture-b@example.test', '+237 6 50 00 00 02', 'owner', 'pro', 'email', 'Fixture', 'B'),
  ('33333333-3333-3333-3333-333333333333', 'fixture-c@example.test', NULL, 'owner', 'free', 'google', 'Fixture', 'C'),
  ('55555555-5555-5555-5555-555555555555', NULL, '+241 06 00 00 01', 'owner', 'free', 'email', 'Fixture', 'E'),
  ('66666666-6666-6666-6666-666666666666', 'fixture-f@example.test', '+237650000003', 'owner', 'team', 'apple', 'Fixture', 'F'),
  ('88888888-8888-8888-8888-888888888888', 'fixture-h@example.test', '( )', 'owner', 'free', 'email', 'Fixture', 'H'),
  ('99999999-9999-9999-9999-999999999999', 'fixture-s@example.test', '+237650000009', 'superadmin', 'free', 'email', 'Fixture', 'S');
INSERT INTO shop (id, "userId", name, address, category, country) VALUES
  ('a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', '11111111-1111-1111-1111-111111111111', 'Shop A1', 'Ville A', 'retail', 'CM'),
  ('e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1', '55555555-5555-5555-5555-555555555555', 'Shop E1', 'Ville E', 'retail', 'SN');
INSERT INTO "user" (id, username, phone, role, "shopId", provider, "firstName", "lastName") VALUES
  ('44444444-4444-4444-4444-444444444444', 'vendeur.d', NULL, 'seller', 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'email', 'Fixture', 'D'),
  ('77777777-7777-7777-7777-777777777777', 'vendeur.g', '6 99 00 00 05', 'seller', 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'email', 'Fixture', 'G');
INSERT INTO subscription ("ownerId", plan, status, "startedAt", "endsAt", "gracePeriodEndsAt", "paymentMethod", "amountPaid") VALUES
  ('11111111-1111-1111-1111-111111111111', 'pro', 'active', now() - interval '10 days', now() + interval '20 days', NULL, 'orange_money', 5000),
  ('22222222-2222-2222-2222-222222222222', 'pro', 'expired', now() - interval '40 days', now() - interval '10 days', NULL, 'momo', 5000),
  ('66666666-6666-6666-6666-666666666666', 'team', 'active', now() - interval '32 days', now() - interval '2 days', now() + interval '5 days', 'apple', 9000);
-- I1 correct (depuis develop) ; I2 total faux (avant develop) ; I4 TVA ignorée ; I5 entièrement remisé ; I6 jour de la bascule
INSERT INTO invoice (id, "userId", "shopId", "customerName", subtotal, tax, discount, "deliveryFee", total, status, type, "createdAt", date) VALUES
  ('f1f1f1f1-f1f1-f1f1-f1f1-f1f1f1f1f1f1', '11111111-1111-1111-1111-111111111111', 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'Client X', 1000, 193, 0, 0, 1193, 'paid', 'sale', now(), '2026-11-02'),
  ('f2f2f2f2-f2f2-f2f2-f2f2-f2f2f2f2f2f2', '11111111-1111-1111-1111-111111111111', 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'Client Y', 1000, 193, 0, 0, 1500, 'pending', 'sale', '2026-09-01 10:00:00', '2026-09-01'),
  ('f4f4f4f4-f4f4-f4f4-f4f4-f4f4f4f4f4f4', '11111111-1111-1111-1111-111111111111', 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'Client W', 2000, 0, 0, 0, 2000, 'pending', 'quote', '2026-10-01 10:00:00', '2026-10-01'),
  ('f5f5f5f5-f5f5-f5f5-f5f5-f5f5f5f5f5f5', '11111111-1111-1111-1111-111111111111', 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'Client V', 500, 0, 500, 0, 0, 'paid', 'sale', '2026-10-02 10:00:00', '2026-10-02'),
  ('f6f6f6f6-f6f6-f6f6-f6f6-f6f6f6f6f6f6', '11111111-1111-1111-1111-111111111111', 'a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'Client U', 1000, 193, 0, 0, 1193, 'pending', 'sale', '2026-10-26 08:00:00', '2026-10-26');
INSERT INTO invoice_item ("invoiceId", reference, name, quantity, "unitPrice", total) VALUES
  ('f1f1f1f1-f1f1-f1f1-f1f1-f1f1f1f1f1f1', 'R1', 'Article', 2, 500, 1000),
  ('f2f2f2f2-f2f2-f2f2-f2f2-f2f2f2f2f2f2', 'R2', 'Article', 1, 1000, 1000),
  ('f4f4f4f4-f4f4-f4f4-f4f4-f4f4f4f4f4f4', 'R4', 'Article', 1, 2000, 2000),
  ('f5f5f5f5-f5f5-f5f5-f5f5-f5f5f5f5f5f5', 'R5', 'Article', 1, 500, 500),
  ('f6f6f6f6-f6f6-f6f6-f6f6-f6f6f6f6f6f6', 'R6', 'Article', 1, 1000, 1000);
INSERT INTO invoice_payment ("invoiceId", amount, "dueDate", "isPaid", "paidDate") VALUES
  ('f1f1f1f1-f1f1-f1f1-f1f1-f1f1f1f1f1f1', 1193, '2026-11-02', true, '2026-11-02');
-- I3 : boutique hors CM (SN), montants décimaux
INSERT INTO invoice (id, "userId", "shopId", "customerName", subtotal, tax, discount, "deliveryFee", total, status, type, "createdAt", date) VALUES
  ('f3f3f3f3-f3f3-f3f3-f3f3-f3f3f3f3f3f3', '55555555-5555-5555-5555-555555555555', 'e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1', 'Client Z', 100.50, 18, 0, 0, 118.50, 'pending', 'sale', now(), '2026-11-02');
INSERT INTO invoice_item ("invoiceId", reference, name, quantity, "unitPrice", total) VALUES
  ('f3f3f3f3-f3f3-f3f3-f3f3-f3f3f3f3f3f3', 'R3', 'Article', 1, 100.50, 100.50);
INSERT INTO account_credit ("ownerId", amount) VALUES ('22222222-2222-2222-2222-222222222222', 2500);
INSERT INTO usage_counter ("ownerId", metric, "periodMonth", used) VALUES
  ('11111111-1111-1111-1111-111111111111', 'sms', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM'), 3),
  ('11111111-1111-1111-1111-111111111111', 'sms_free', 'all', 5),
  ('11111111-1111-1111-1111-111111111111', 'invoices', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM'), 2);
INSERT INTO sms_recharge_request ("userId", "smsQuantity", "amountDue", status, "paymentMethod", "completedAt") VALUES
  ('11111111-1111-1111-1111-111111111111', 100, 2000, 'completed', 'orange', now() - interval '3 days'),
  ('55555555-5555-5555-5555-555555555555', 50, 1000, 'pending', 'mtn', NULL);
INSERT INTO refresh_token (jti, "familyId", "userId", "expiresAt") VALUES
  ('jti-fixture-a', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', now() + interval '30 days');
INSERT INTO push_token ("userId", token, platform) VALUES
  ('44444444-4444-4444-4444-444444444444', 'fixture-push-token-d', 'android');
COMMIT;
