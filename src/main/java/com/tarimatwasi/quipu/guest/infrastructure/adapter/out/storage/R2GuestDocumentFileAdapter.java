package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.storage;

import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Clients;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2FailureCause;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import com.tarimatwasi.quipu.shared.file.FileKind;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * The documents of the onboarding in the Cloudflare R2 bucket (RN-37), under their own prefix
 * {@code guest-documents/}. The clients are the shared {@link R2Clients}; without R2 credentials
 * (local profile, tests) using the port fails with {@link GuestDocumentStorageException}. Neither
 * the key nor the signed URL is ever logged.
 */
@Component
public class R2GuestDocumentFileAdapter implements GuestDocumentFilePort {

  private static final Logger LOGGER = LoggerFactory.getLogger(R2GuestDocumentFileAdapter.class);

  private static final String KEY_PREFIX = "guest-documents/";

  private final R2Properties properties;
  private final R2Clients clients;

  R2GuestDocumentFileAdapter(R2Properties properties, R2Clients clients) {
    this.properties = properties;
    this.clients = clients;
  }

  @Override
  public String store(byte[] content, FileKind kind) {
    String key = KEY_PREFIX + UUID.randomUUID();
    try {
      client()
          .putObject(
              PutObjectRequest.builder()
                  .bucket(properties.bucketName())
                  .key(key)
                  .contentType(kind.contentType())
                  .contentLength((long) content.length)
                  .build(),
              RequestBody.fromBytes(content));
    } catch (SdkException e) {
      LOGGER.warn("R2 put of a guest document failed: {}", R2FailureCause.of(e));
      throw new GuestDocumentStorageException("The document could not be stored", e);
    }
    return key;
  }

  @Override
  public void delete(String key) {
    try {
      client()
          .deleteObject(
              DeleteObjectRequest.builder().bucket(properties.bucketName()).key(key).build());
    } catch (SdkException e) {
      LOGGER.warn("R2 delete of a guest document failed: {}", R2FailureCause.of(e));
      throw new GuestDocumentStorageException("The document could not be removed", e);
    }
  }

  @Override
  public String signedUrl(String key, FileKind kind) {
    try {
      return presigner()
          .presignGetObject(
              GetObjectPresignRequest.builder()
                  .signatureDuration(GuestDocument.URL_VALIDITY)
                  .getObjectRequest(
                      GetObjectRequest.builder()
                          .bucket(properties.bucketName())
                          .key(key)
                          // The type of the content, not a guess of the browser, and a download
                          // under a fixed name: a document never runs on the domain of the bucket.
                          .responseContentType(kind.contentType())
                          .responseContentDisposition(
                              "attachment; filename=\"documento." + kind.extension() + "\"")
                          .build())
                  .build())
          .url()
          .toString();
    } catch (SdkException e) {
      LOGGER.warn("R2 presign of a guest document failed: {}", R2FailureCause.of(e));
      throw new GuestDocumentStorageException("The document could not be signed", e);
    }
  }

  private S3Client client() {
    return clients.client().orElseThrow(this::notConfigured);
  }

  private S3Presigner presigner() {
    return clients.presigner().orElseThrow(this::notConfigured);
  }

  /** TAR-356: the 503 of an upload without R2 says only "storage"; the log says why. */
  private GuestDocumentStorageException notConfigured() {
    LOGGER.warn("R2 is not configured: the guest document cannot be stored, removed or signed");
    return new GuestDocumentStorageException("R2 is not configured");
  }
}
