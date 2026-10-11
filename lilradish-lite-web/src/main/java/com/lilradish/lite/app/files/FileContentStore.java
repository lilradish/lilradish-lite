package com.lilradish.lite.app.files;

import java.io.InputStream;
import java.util.UUID;

/** One content by its server-minted identifier, independent of its metadata and holders. */
interface FileContentStore {

    void put(UUID id, InputStream content);

    InputStream open(UUID id);
}
