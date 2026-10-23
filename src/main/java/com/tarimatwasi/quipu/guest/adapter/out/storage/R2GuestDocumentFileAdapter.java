package com.tarimatwasi.quipu.guest.adapter.out.storage;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import com.tarimatwasi.quipu.shared.file.FileKind;
import jakarta.annotation.PreDestroy;
import java.net.URI;
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
 * The documents of the onboarding in the Cloudflare R2 bucket (RN-37), under their own prefix
 * {@code guest-documents/}. The clients are built on the first use, so the application starts
 * without R2 credentials (local profile, tests); using the port without them fails with {@link
 * GuestDocumentStorageException}. Neither the key nor the signed URL is ever logged.
 */
@Component
public class R2GuestDocumentFileAdapter implements GuestDocumentFilePort {

  private static final String KEY_PREFIX = "guest-documents/";

  private final R2Properties properties;
  private final Once<S3Client> client = new Once<>(this::buildClient);
  private final Once<S3Presigner> presigner = new Once<>(this::buildPresigner);

  R2GuestDocumentFileAdapter(R2Properties properties) {
    this.properties = properties;
  }

  @Override
  public String store(byte[] content, FileKind kind) {
    String key = KEY_PREFIX + UUID.randomUUID();
    try {
      client
          .get()
          .putObject(
              PutObjectRequest.builder()
                  .bucket(properties.bucketName())
                  .key(key)
                  .contentType(kind.contentType())
                  .contentLength((long) content.length)
                  .build(),
              RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw new GuestDocumentStorageException("The document could not be stored", e);
    }
    return key;
  }

  @Override
  public void delete(String key) {
    try {
      client
          .get()
          .deleteObject(
              DeleteObjectRequest.builder().bucket(properties.bucketName()).key(key).build());
    } catch (SdkException e) {
      throw new GuestDocumentStorageException("The document could not be removed", e);
    }
  }

  @Override
  public String signedUrl(String key, FileKind kind) {
    try {
      return presigner
          .get()
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
      throw new GuestDocumentStorageException("The document could not be signed", e);
    }
  }

  private S3Client buildClient() {
    requireConfigured();
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
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
      throw new GuestDocumentStorageException("R2 is not configured");
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
