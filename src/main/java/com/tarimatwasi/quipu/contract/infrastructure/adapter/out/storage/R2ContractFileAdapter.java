package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.storage;

import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Clients;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2FailureCause;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import java.time.Duration;
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
 * The PDFs of the contracts in the Cloudflare R2 bucket (RF-15). The clients are the shared {@link
 * R2Clients}; without R2 credentials (local profile, tests) using the port fails with {@link
 * ContractFileStorageException}.
 */
@Component
public class R2ContractFileAdapter implements ContractFilePort {

  private static final Logger LOGGER = LoggerFactory.getLogger(R2ContractFileAdapter.class);

  private static final String KEY_PREFIX = "contracts/";
  private static final String PDF = "application/pdf";

  /** The validity of a signed URL (the contract of the BFF says 15 minutes). */
  static final Duration URL_VALIDITY = Duration.ofMinutes(15);

  private final R2Properties properties;
  private final R2Clients clients;

  R2ContractFileAdapter(R2Properties properties, R2Clients clients) {
    this.properties = properties;
    this.clients = clients;
  }

  @Override
  public String store(byte[] content) {
    String key = KEY_PREFIX + UUID.randomUUID() + ".pdf";
    try {
      client()
          .putObject(
              PutObjectRequest.builder()
                  .bucket(properties.bucketName())
                  .key(key)
                  .contentType(PDF)
                  .contentLength((long) content.length)
                  .build(),
              RequestBody.fromBytes(content));
    } catch (SdkException e) {
      LOGGER.warn("R2 put of a contract file failed: {}", R2FailureCause.of(e));
      throw new ContractFileStorageException("The contract file could not be stored", e);
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
      LOGGER.warn("R2 delete of a contract file failed: {}", R2FailureCause.of(e));
      throw new ContractFileStorageException("The contract file could not be removed", e);
    }
  }

  @Override
  public String signedUrl(String key) {
    try {
      return presigner()
          .presignGetObject(
              GetObjectPresignRequest.builder()
                  .signatureDuration(URL_VALIDITY)
                  .getObjectRequest(
                      GetObjectRequest.builder()
                          .bucket(properties.bucketName())
                          .key(key)
                          .responseContentType(PDF)
                          // A fixed name, never the uploaded one: an active PDF must not run inline
                          // on the domain of the bucket.
                          .responseContentDisposition("attachment; filename=\"contrato.pdf\"")
                          .build())
                  .build())
          .url()
          .toString();
    } catch (SdkException e) {
      LOGGER.warn("R2 presign of a contract file failed: {}", R2FailureCause.of(e));
      throw new ContractFileStorageException("The contract file could not be signed", e);
    }
  }

  private S3Client client() {
    return clients.client().orElseThrow(this::notConfigured);
  }

  private S3Presigner presigner() {
    return clients.presigner().orElseThrow(this::notConfigured);
  }

  /** TAR-356: the 503 of an upload without R2 says only "storage"; the log says why. */
  private ContractFileStorageException notConfigured() {
    LOGGER.warn("R2 is not configured: the contract file cannot be stored, removed or signed");
    return new ContractFileStorageException("R2 is not configured");
  }
}
