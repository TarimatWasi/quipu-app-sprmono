package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.storage;

import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * The PDFs of the contracts in the Cloudflare R2 bucket (RF-15). The clients are built on the first
 * use, so the application starts without R2 credentials (local profile, tests); using the port
 * without them fails with {@link ContractFileStorageException}.
 */
@Component
public class R2ContractFileAdapter implements ContractFilePort {

  private static final String KEY_PREFIX = "contracts/";
  private static final String PDF = "application/pdf";

  /** The validity of a signed URL (the contract of the BFF says 15 minutes). */
  static final Duration URL_VALIDITY = Duration.ofMinutes(15);

  private final R2Properties properties;
  private final Once<S3Client> client = new Once<>(this::buildClient);
  private final Once<S3Presigner> presigner = new Once<>(this::buildPresigner);

  R2ContractFileAdapter(R2Properties properties) {
    this.properties = properties;
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
      throw new ContractFileStorageException("The contract file could not be signed", e);
    }
  }

  private S3Client client() {
    return client.get();
  }

  private S3Presigner presigner() {
    return presigner.get();
  }

  private S3Client buildClient() {
    requireConfigured();
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        // TAR-204: a bucket that hangs ends the call; the failure is the 503 of the storage.
        .overrideConfiguration(properties.clientOverrides())
        .region(Region.of("auto"))
        .credentialsProvider(credentials())
        .serviceConfiguration(pathStyle())
        // R2 does not take the optional checksums that SDK 2.30+ adds by default.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  private S3Presigner buildPresigner() {
    requireConfigured();
    return S3Presigner.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(Region.of("auto"))
        .credentialsProvider(credentials())
        .serviceConfiguration(pathStyle())
        .build();
  }

  private StaticCredentialsProvider credentials() {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
  }

  private static S3Configuration pathStyle() {
    return S3Configuration.builder().pathStyleAccessEnabled(true).build();
  }

  private void requireConfigured() {
    if (properties.endpoint().isBlank()
        || properties.bucketName().isBlank()
        || properties.accessKeyId().isBlank()
        || properties.secretAccessKey().isBlank()) {
      throw new ContractFileStorageException("R2 is not configured");
    }
  }

  @PreDestroy
  void close() {
    client.close();
    presigner.close();
  }

  /** A value built on first use, and closed only if it was ever built. */
  private static final class Once<T extends SdkAutoCloseable> {

    private final Supplier<T> factory;
    private @Nullable T value;

    Once(Supplier<T> factory) {
      this.factory = factory;
    }

    synchronized T get() {
      if (value == null) {
        value = factory.get();
      }
      return value;
    }

    synchronized void close() {
      if (value != null) {
        value.close();
      }
    }
  }
}
