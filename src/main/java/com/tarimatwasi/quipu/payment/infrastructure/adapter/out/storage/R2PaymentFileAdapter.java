package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.storage;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
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
 * The vouchers in the Cloudflare R2 bucket (RF-15), under {@code vouchers/}. The clients are the
 * shared {@link R2Clients}; without R2 credentials (local profile, tests) using the port fails with
 * {@link PaymentFileStorageException}.
 */
@Component
public class R2PaymentFileAdapter implements PaymentFilePort {

  private static final Logger LOGGER = LoggerFactory.getLogger(R2PaymentFileAdapter.class);

  private static final String KEY_PREFIX = "vouchers/";

  /** The validity of a signed URL (the contract of the BFF says 15 minutes). */
  static final Duration URL_VALIDITY = Duration.ofMinutes(15);

  private final R2Properties properties;
  private final R2Clients clients;

  R2PaymentFileAdapter(R2Properties properties, R2Clients clients) {
    this.properties = properties;
    this.clients = clients;
  }

  @Override
  public String store(byte[] content, VoucherType type) {
    String key = KEY_PREFIX + UUID.randomUUID();
    try {
      client()
          .putObject(
              PutObjectRequest.builder()
                  .bucket(properties.bucketName())
                  .key(key)
                  .contentType(type.contentType())
                  .contentLength((long) content.length)
                  .build(),
              RequestBody.fromBytes(content));
    } catch (SdkException e) {
      LOGGER.warn("R2 put of a voucher failed: {}", R2FailureCause.of(e));
      throw new PaymentFileStorageException("The voucher could not be stored", e);
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
      LOGGER.warn("R2 delete of a voucher failed: {}", R2FailureCause.of(e));
      throw new PaymentFileStorageException("The voucher could not be removed", e);
    }
  }

  @Override
  public String signedUrl(String key, VoucherType type) {
    try {
      return presigner()
          .presignGetObject(
              GetObjectPresignRequest.builder()
                  .signatureDuration(URL_VALIDITY)
                  .getObjectRequest(
                      GetObjectRequest.builder()
                          .bucket(properties.bucketName())
                          .key(key)
                          // The type of the content, not a guess of the browser, and a download
                          // under a fixed name: a voucher never runs on the domain of the bucket.
                          .responseContentType(type.contentType())
                          .responseContentDisposition(
                              "attachment; filename=\"comprobante." + type.extension() + "\"")
                          .build())
                  .build())
          .url()
          .toString();
    } catch (SdkException e) {
      LOGGER.warn("R2 presign of a voucher failed: {}", R2FailureCause.of(e));
      throw new PaymentFileStorageException("The voucher could not be signed", e);
    }
  }

  private S3Client client() {
    return clients.client().orElseThrow(this::notConfigured);
  }

  private S3Presigner presigner() {
    return clients.presigner().orElseThrow(this::notConfigured);
  }

  /** TAR-356: the 503 of an upload without R2 says only "storage"; the log says why. */
  private PaymentFileStorageException notConfigured() {
    LOGGER.warn("R2 is not configured: the voucher cannot be stored, removed or signed");
    return new PaymentFileStorageException("R2 is not configured");
  }
}
