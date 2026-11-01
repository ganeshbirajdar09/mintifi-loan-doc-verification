package com.loanverify.api.dto;

import com.loanverify.domain.DocumentCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Document metadata. The file itself lives in external storage. We only keep a reference to it.
 * {@code category} is required when adding and ignored (taken from the path) when replacing.
 */
public record DocumentMetadataRequest(
        DocumentCategory category,

        @NotBlank @Size(max = 512)
        @Pattern(regexp = "^(?!.*\\.\\.)(s3|gs|azblob|docstore)://[A-Za-z0-9][A-Za-z0-9._\\-/]*$",
                message = "must be a storage reference like s3://bucket/key (schemes: s3, gs, azblob, docstore)")
        String fileRef,

        @NotBlank @Size(max = 255)
        @Pattern(regexp = "^[^/\\\\\\p{Cntrl}<>]+$", message = "must be a plain file name without path separators")
        String filename,

        @NotNull @Pattern(regexp = "^[A-Fa-f0-9]{64}$", message = "must be a hex SHA-256 digest")
        String contentHash,

        @Size(max = 100) @Pattern(regexp = "^[a-z]+/[a-z0-9.+\\-]+$", message = "must be a MIME type")
        String contentType,

        @PositiveOrZero @Max(50L * 1024 * 1024)
        Long sizeBytes) {
}
