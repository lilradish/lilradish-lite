package org.lilradish.lite.app.files;

import java.io.InputStream;
import java.util.Optional;
import org.lilradish.lite.domain.file.MediaTypeName;

/** A type inferred from stored content, when an implementation is available. */
public interface ContentTypeRecognition {

    Optional<MediaTypeName> recognize(InputStream content);
}
