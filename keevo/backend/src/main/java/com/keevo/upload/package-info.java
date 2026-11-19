// Envoi de fichiers : les images choisies dans l'app (logo, photo de produit, média d'en-tête WhatsApp) sont déposées
// chez Cloudinary, comme api-bref ; la réponse est le lien public à enregistrer. Dépend du noyau commun seulement.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared"}
)
package com.keevo.upload;
