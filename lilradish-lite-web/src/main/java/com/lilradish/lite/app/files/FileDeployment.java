package com.lilradish.lite.app.files;

import com.lilradish.lite.domain.declaration.FieldShape;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.unit.DataSize;

/** Deployment limits for one file, one field value and all files handed over at once. */
@Lazy(false)
@ConfigurationProperties(prefix = "lilradish.files", ignoreUnknownFields = false)
public record FileDeployment(DataSize maximumSize, Integer maximumPerField, DataSize maximumTotalSize) {

    public FileDeployment {
        if (maximumSize == null || maximumSize.toBytes() < 1) {
            throw new IllegalArgumentException("lilradish.files.maximum-size must be positive");
        }
        if (maximumPerField == null || maximumPerField < 1) {
            throw new IllegalArgumentException("lilradish.files.maximum-per-field must be positive");
        }
        if (maximumTotalSize == null || maximumTotalSize.toBytes() < 1) {
            throw new IllegalArgumentException("lilradish.files.maximum-total-size must be positive");
        }
        if (!FieldShape.File.maximumBytesFit(maximumSize.toBytes())) {
            throw new IllegalArgumentException(
                    "lilradish.files.maximum-size must not exceed " + FieldShape.File.LARGEST_BYTES + " bytes");
        }
        if (!FieldShape.File.maximumBytesFit(maximumTotalSize.toBytes())) {
            throw new IllegalArgumentException(
                    "lilradish.files.maximum-total-size must not exceed " + FieldShape.File.LARGEST_BYTES + " bytes");
        }
        if (maximumTotalSize.toBytes() < maximumSize.toBytes()) {
            throw new IllegalArgumentException("lilradish.files.maximum-total-size must not be less than maximum-size");
        }
    }
}
