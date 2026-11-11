-- Story 24.6 — versioned SYNTHETIC Bref owner covering the annex tables that are empty in the 03/11 dump
-- (whatsapp_account, whatsapp_chat_message, customer_campaign…): one owner, one shop, one seller, one customer, six
-- documents, a connected WhatsApp Business account, templates, campaigns and their messages, an inbox, AI conversations
-- and notifications — every edge case of the 24.6 matrix. Fixed ids (prefix 00000246-), fake phones (+23769002460x),
-- fake Meta ids and token: no real data. Used by BrefAnnexesImportFullContextTest and by the live check (applied on top
-- of a restored Bref copy). Plain SQL, idempotent (ON CONFLICT DO NOTHING).

INSERT INTO public."user" (id, phone, role, "isActive", "isVerified", "firstName", "lastName", "createdAt", "updatedAt")
VALUES ('00000246-0000-4000-8000-000000000001', '+237690024601', 'owner', true, true, 'Propriétaire', 'Synthétique',
        '2026-09-01 08:00:00', '2026-09-01 08:00:00')
ON CONFLICT DO NOTHING;

INSERT INTO public.shop (id, "userId", name, address, category, phone, country, "createdAt", "updatedAt")
VALUES ('00000246-0000-4000-8000-000000000011', '00000246-0000-4000-8000-000000000001', 'Boutique Synthétique 246',
        'Adresse synthétique', 'fashion', '690024699', 'CM', '2026-09-01 08:00:00', '2026-09-01 08:00:00')
ON CONFLICT DO NOTHING;

-- a seller of the shop (AI conversation, notification, chat reply)
INSERT INTO public."user" (id, phone, role, "shopId", "isActive", "isVerified", "firstName", "createdAt", "updatedAt")
VALUES ('00000246-0000-4000-8000-000000000002', '+237690024602', 'seller', '00000246-0000-4000-8000-000000000011',
        true, true, 'Vendeur', '2026-09-02 08:00:00', '2026-09-02 08:00:00')
ON CONFLICT DO NOTHING;

INSERT INTO public.customer (id, "shopId", name, phone, "marketingConsentAt", "createdAt", "updatedAt")
VALUES ('00000246-0000-4000-8000-000000000021', '00000246-0000-4000-8000-000000000011', 'Cliente Synthétique',
        '+237690024621', '2026-09-03 08:00:00', '2026-09-03 08:00:00', '2026-09-03 08:00:00')
ON CONFLICT DO NOTHING;

-- documents: pending, paid, cancelled (indexed for the portal), quote, deleted, no phone (not indexed)
INSERT INTO public.invoice (id, "userId", "shopId", "customerName", "customerPhone", subtotal, total, status, type,
                           date, "createdAt", "updatedAt", "deletedAt", "cancelledAt")
VALUES
  ('00000246-0000-4000-8000-000000000101', '00000246-0000-4000-8000-000000000001',
   '00000246-0000-4000-8000-000000000011', 'Cliente Synthétique', '+237690024621', 5000, 5000, 'pending', 'sale',
   '2026-10-01', '2026-10-01 09:00:00', '2026-10-01 09:00:00', NULL, NULL),
  ('00000246-0000-4000-8000-000000000102', '00000246-0000-4000-8000-000000000001',
   '00000246-0000-4000-8000-000000000011', 'Cliente Synthétique', '690024621', 7000, 7000, 'paid', 'sale',
   '2026-10-02', '2026-10-02 09:00:00', '2026-10-02 09:00:00', NULL, NULL),
  ('00000246-0000-4000-8000-000000000103', '00000246-0000-4000-8000-000000000001',
   '00000246-0000-4000-8000-000000000011', 'Cliente Synthétique', '+237690024621', 3000, 3000, 'cancelled', 'sale',
   '2026-10-03', '2026-10-03 09:00:00', '2026-10-03 09:00:00', NULL, '2026-10-03 10:00:00'),
  ('00000246-0000-4000-8000-000000000104', '00000246-0000-4000-8000-000000000001',
   '00000246-0000-4000-8000-000000000011', 'Cliente Synthétique', '+237690024621', 9000, 9000, 'pending', 'quote',
   NULL, '2026-10-04 09:00:00', '2026-10-04 09:00:00', NULL, NULL),
  ('00000246-0000-4000-8000-000000000105', '00000246-0000-4000-8000-000000000001',
   '00000246-0000-4000-8000-000000000011', 'Cliente Synthétique', '+237690024621', 1000, 1000, 'pending', 'sale',
   '2026-10-05', '2026-10-05 09:00:00', '2026-10-05 09:00:00', '2026-10-06 09:00:00', NULL),
  ('00000246-0000-4000-8000-000000000106', '00000246-0000-4000-8000-000000000001',
   '00000246-0000-4000-8000-000000000011', 'Client sans téléphone', NULL, 2000, 2000, 'paid', 'sale',
   '2026-10-06', '2026-10-06 09:00:00', '2026-10-06 09:00:00', NULL, NULL)
ON CONFLICT DO NOTHING;

-- WhatsApp Business account (fake Meta ids, fake token)
INSERT INTO public.whatsapp_account (id, "ownerId", "wabaId", "phoneNumberId", "displayPhoneNumber", "verifiedName",
                                     "accessToken", status, "createdAt", "updatedAt")
VALUES ('00000246-0000-4000-8000-000000000201', '00000246-0000-4000-8000-000000000001', '2460000000000001',
        '2460000000000002', '+237 6 90 02 46 99', 'Boutique Synthétique 246', 'EAAG-synthetic-token-246', 'connected',
        '2026-09-05 08:00:00', '2026-09-05 08:00:00')
ON CONFLICT DO NOTHING;

-- templates: approved, draft with a media sample, rejected, invalid name, reserved name
INSERT INTO public.owner_whatsapp_template (id, "ownerId", name, category, language, "headerFormat", "headerText",
                                            "headerMediaUrl", "bodyText", "bodyExamples", "footerText", status,
                                            "metaTemplateId", "rejectedReason", "createdAt", "updatedAt")
VALUES
  ('00000246-0000-4000-8000-000000000301', '00000246-0000-4000-8000-000000000001', 'promo_novembre', 'MARKETING',
   'fr', 'TEXT', 'Promo', NULL, 'Bonjour {{1}}, -10 % chez {{2}}', '["Awa", "Boutique"]', 'Bref Point', 'APPROVED',
   '9246000000000001', NULL, '2026-09-10 08:00:00', '2026-09-11 08:00:00'),
  ('00000246-0000-4000-8000-000000000302', '00000246-0000-4000-8000-000000000001', 'nouveautes_media', 'MARKETING',
   'fr', 'IMAGE', NULL, 'https://example.invalid/sample.png', 'Découvrez nos nouveautés', '[]', NULL, 'DRAFT',
   NULL, NULL, '2026-09-12 08:00:00', '2026-09-12 08:00:00'),
  ('00000246-0000-4000-8000-000000000303', '00000246-0000-4000-8000-000000000001', 'relance_refusee', 'UTILITY',
   'fr', 'NONE', NULL, NULL, 'Votre facture {{1}} est en attente', '["FAC-1"]', NULL, 'REJECTED', '9246000000000003',
   'INVALID_FORMAT', '2026-09-13 08:00:00', '2026-09-13 08:00:00'),
  ('00000246-0000-4000-8000-000000000304', '00000246-0000-4000-8000-000000000001', 'Nom Invalide', 'MARKETING',
   'fr', 'NONE', NULL, NULL, 'Texte', '[]', NULL, 'DRAFT', NULL, NULL, '2026-09-14 08:00:00', '2026-09-14 08:00:00'),
  ('00000246-0000-4000-8000-000000000305', '00000246-0000-4000-8000-000000000001', 'bref_facture', 'UTILITY',
   'fr', 'NONE', NULL, NULL, 'Texte réservé', '[]', NULL, 'APPROVED', NULL, NULL, '2026-09-15 08:00:00',
   '2026-09-15 08:00:00')
ON CONFLICT DO NOTHING;

-- campaigns: sent (shop + criteria + variables), scheduled (resumed as draft)
INSERT INTO public.customer_campaign (id, "ownerId", name, description, status, "targetingCriteria", whatsapp,
                                      "targetedCount", "sentCount", "deliveredCount", "readCount", "failedCount",
                                      "scheduledAt", "sentAt", "createdById", "createdAt", "updatedAt")
VALUES
  ('00000246-0000-4000-8000-000000000401', '00000246-0000-4000-8000-000000000001', 'Promo de novembre',
   'Campagne synthétique', 'sent',
   '{"shopIds": ["00000246-0000-4000-8000-000000000011"], "inactiveForDays": 30, "totalSpentMin": 1000}',
   '{"templateName": "promo_novembre", "language": "fr", "headerFormat": "TEXT", "bodyText": "Bonjour {{1}}, -10 % chez {{2}}", "bodyVariables": [{"source": "customerName", "value": "cliente"}, {"source": "shopName"}]}',
   2, 1, 1, 1, 1, NULL, '2026-10-10 10:00:00', '00000246-0000-4000-8000-000000000001', '2026-10-10 09:00:00',
   '2026-10-10 10:05:00'),
  ('00000246-0000-4000-8000-000000000402', '00000246-0000-4000-8000-000000000001', 'Relance programmée', NULL,
   'scheduled', '{}', '{"templateName": "promo_novembre", "language": "fr", "bodyVariables": []}', 0, 0, 0, 0, 0,
   '2026-12-01 09:00:00', NULL, '00000246-0000-4000-8000-000000000001', '2026-10-11 09:00:00',
   '2026-10-11 09:00:00')
ON CONFLICT DO NOTHING;

-- campaign messages: one phone twice (latest kept), one failure, one platform campaign row (not the owner's)
INSERT INTO public.whatsapp_message (id, "campaignId", "userId", phone, "templateName", "waMessageId", status, error,
                                     "createdAt", "updatedAt", "customerCampaignId", "customerId")
VALUES
  ('00000246-0000-4000-8000-000000000501', NULL, NULL, '237690024621', 'promo_novembre', 'wamid.syn246-c1', 'sent',
   NULL, '2026-10-10 10:00:00', '2026-10-10 10:00:00', '00000246-0000-4000-8000-000000000401',
   '00000246-0000-4000-8000-000000000021'),
  ('00000246-0000-4000-8000-000000000502', NULL, NULL, '237690024621', 'promo_novembre', 'wamid.syn246-c2', 'read',
   NULL, '2026-10-10 10:01:00', '2026-10-10 10:30:00', '00000246-0000-4000-8000-000000000401',
   '00000246-0000-4000-8000-000000000021'),
  ('00000246-0000-4000-8000-000000000503', NULL, NULL, '237690024622', 'promo_novembre', NULL, 'failed',
   'Numéro non joignable', '2026-10-10 10:02:00', '2026-10-10 10:02:00', '00000246-0000-4000-8000-000000000401',
   '00000246-0000-4000-8000-000000000022'),
  ('00000246-0000-4000-8000-000000000504', '00000246-0000-4000-8000-000000000999',
   '00000246-0000-4000-8000-000000000001', '237690024601', 'annonce_plateforme', 'wamid.syn246-p', 'sent', NULL,
   '2026-10-12 10:00:00', '2026-10-12 10:00:00', NULL, NULL)
ON CONFLICT DO NOTHING;

-- inbox of the owner's number: in (unread), out template (read), out reply by the seller, in without Meta id,
-- unreadable phone; plus one platform-number row (ownerId NULL, not the owner's)
INSERT INTO public.whatsapp_chat_message (id, "ownerId", "contactPhone", "contactName", direction, type, body,
                                          "waMessageId", status, "readAt", "sentById", "createdAt")
VALUES
  ('00000246-0000-4000-8000-000000000601', '00000246-0000-4000-8000-000000000001', '237690024621', 'Awa S.', 'in',
   'text', 'Bonjour, vous avez la robe ?', 'wamid.syn246-1', NULL, NULL, NULL, '2026-10-15 09:00:00'),
  ('00000246-0000-4000-8000-000000000602', '00000246-0000-4000-8000-000000000001', '237690024621', NULL, 'out',
   'template', 'Bonjour Awa, -10 % chez Boutique', 'wamid.syn246-2', 'read', NULL, NULL, '2026-10-15 09:05:00'),
  ('00000246-0000-4000-8000-000000000603', '00000246-0000-4000-8000-000000000001', '237690024621', NULL, 'out',
   'text', 'Oui, en taille M.', 'wamid.syn246-3', 'delivered', NULL, '00000246-0000-4000-8000-000000000002',
   '2026-10-15 09:10:00'),
  ('00000246-0000-4000-8000-000000000604', '00000246-0000-4000-8000-000000000001', '237690024623', 'Paul T.', 'in',
   'image', NULL, NULL, NULL, '2026-10-16 08:00:00', NULL, '2026-10-16 07:00:00'),
  ('00000246-0000-4000-8000-000000000605', '00000246-0000-4000-8000-000000000001', '12', NULL, 'in', 'text',
   'Numéro illisible', 'wamid.syn246-5', NULL, NULL, NULL, '2026-10-16 07:30:00'),
  ('00000246-0000-4000-8000-000000000606', NULL, '237690024601', NULL, 'in', 'text', 'Message au numéro plateforme',
   'wamid.syn246-6', NULL, NULL, NULL, '2026-10-16 07:40:00')
ON CONFLICT DO NOTHING;

-- AI conversations of the owner and the seller
INSERT INTO public.ai_conversation (id, "userId", title, "messageCount", messages, "aiHistory", "createdAt",
                                    "updatedAt")
VALUES
  ('00000246-0000-4000-8000-000000000701', '00000246-0000-4000-8000-000000000001', 'Ventes du mois', 2,
   '[{"id": "m1", "role": "user", "text": "Ventes du mois ?"}, {"id": "m2", "role": "assistant", "text": "15 000 F", "stats": [{"icon": "cash", "label": "CA", "value": "15 000 F"}]}]',
   '[{"role": "user", "content": "Ventes du mois ?"}, {"role": "assistant", "content": "15 000 F"}]',
   '2026-10-20 08:00:00', '2026-10-20 08:01:00'),
  ('00000246-0000-4000-8000-000000000702', '00000246-0000-4000-8000-000000000002', 'Stock', 1,
   '[{"id": "m1", "role": "user", "text": "Quel produit manque ?"}]',
   '[{"role": "user", "content": "Quel produit manque ?"}]', '2026-10-21 08:00:00', '2026-10-21 08:00:00')
ON CONFLICT DO NOTHING;

-- notifications: owner unread (invoice), owner read (payment), seller (system)
INSERT INTO public.notification (id, "userId", type, channel, title, message, "isRead", metadata, "createdAt",
                                 "updatedAt")
VALUES
  ('00000246-0000-4000-8000-000000000801', '00000246-0000-4000-8000-000000000001', 'invoice', 'push',
   'Nouvelle facture', 'Facture de 5 000 F créée', false,
   '{"invoiceId": "00000246-0000-4000-8000-000000000101", "amount": 5000.0}', '2026-10-01 09:00:00',
   '2026-10-01 09:00:00'),
  ('00000246-0000-4000-8000-000000000802', '00000246-0000-4000-8000-000000000001', 'payment', 'push',
   'Paiement reçu', 'Paiement de 7 000 F reçu', true, '{"invoiceId": "00000246-0000-4000-8000-000000000102"}',
   '2026-10-02 09:00:00', '2026-10-02 12:00:00'),
  ('00000246-0000-4000-8000-000000000803', '00000246-0000-4000-8000-000000000002', 'system', 'in_app',
   'Bienvenue', 'Vous avez rejoint la boutique', false,
   '{"shopId": "00000246-0000-4000-8000-000000000011", "shopName": "Boutique Synthétique 246"}',
   '2026-09-02 08:00:00', '2026-09-02 08:00:00')
ON CONFLICT DO NOTHING;

-- browser subscriptions of the owner and the seller (fake push endpoints and keys)
INSERT INTO public.web_push_subscription (id, "userId", endpoint, p256dh, auth, "userAgent", "createdAt", "updatedAt")
VALUES
  ('00000246-0000-4000-8000-000000000901', '00000246-0000-4000-8000-000000000001',
   'https://push.example.invalid/send/synthetic-246-owner', 'BSyntheticP256dhKeyOwner246', 'SyntheticAuth246a',
   'Mozilla/5.0 (synthétique)', '2026-09-20 08:00:00', '2026-09-20 08:00:00'),
  ('00000246-0000-4000-8000-000000000902', '00000246-0000-4000-8000-000000000002',
   'https://push.example.invalid/send/synthetic-246-seller', 'BSyntheticP256dhKeySeller246', 'SyntheticAuth246b',
   NULL, '2026-09-21 08:00:00', '2026-09-21 08:00:00')
ON CONFLICT DO NOTHING;
