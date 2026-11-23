package com.keevo.upload.domain.port.out;

/** Où les images déposées sont conservées (Cloudinary en production). */
public interface ImageStorage {

    /** Une image conservée : son lien public (https) et son identifiant chez le stockage. */
    record StoredImage(String url, String publicId) {
    }

    /** Vrai quand le stockage est configuré ; sinon aucun envoi n'est accepté. */
    boolean available();

    /**
     * Dépose l'image dans le dossier {@code folder} (déjà contrôlé : {@code <code entreprise>/<dossier>}), réduite à 1024 px au plus sans recadrage ni
     * agrandissement.
     *
     * @throws java.io.IOException le stockage est injoignable ou refuse l'image
     */
    StoredImage store(byte[] content, String folder) throws java.io.IOException;
}
