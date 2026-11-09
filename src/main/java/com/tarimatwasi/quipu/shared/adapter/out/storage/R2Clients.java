package com.tarimatwasi.quipu.shared.adapter.out.storage;

import java.net.URI;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The one S3 client and the one presigner of the Cloudflare R2 bucket, shared by every storage
 * adapter (TAR-350). They are built when the application starts, not on the first upload: the AWS
 * SDK loads some 3,000 classes (about 11 MB of Metaspace) the first time a client is built, and
 * that load must fail the startup (the health check of the deploy) rather than the request of a
 * user.
 *
 * <p>Without R2 credentials (local profile, tests) nothing is built and the application still
 * starts; each adapter turns the empty value into the storage exception of its own port.
 */
@Component
public class R2Clients implements AutoCloseable {

  private final @Nullable S3Client client;
  private final @Nullable S3Presigner presigner;

  public R2Clients(R2Properties properties) {
    if (isConfigured(properties)) {
      this.client = buildClient(properties);
      this.presigner = buildPresigner(properties);
    } else {
      this.client = null;
      this.presigner = null;
    }
  }

  /** The client of the bucket, empty when R2 is not configured. */
  public Optional<S3Client> client() {
    return Optional.ofNullable(client);
  }

  /** The presigner of the bucket, empty when R2 is not configured. */
  public Optional<S3Presigner> presigner() {
    return Optional.ofNullable(presigner);
  }

  @Override
  public void close() {
    if (client != null) {
      client.close();
    }
    if (presigner != null) {
      presigner.close();
    }
  }

  private static boolean isConfigured(R2Properties properties) {
    return !(properties.endpoint().isBlank()
        || properties.bucketName().isBlank()
        || properties.accessKeyId().isBlank()
        || properties.secretAccessKey().isBlank());
  }

  private static S3Client buildClient(R2Properties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        // TAR-204: a bucket that hangs ends the call; the failure is the 503 of the storage.
        .overrideConfiguration(properties.clientOverrides())
        .region(Region.of("auto"))
        .credentialsProvider(credentials(properties))
        .serviceConfiguration(pathStyle())
        // R2 does not take the optional checksums that SDK 2.30+ adds by default.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  private static S3Presigner buildPresigner(R2Properties properties) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(Region.of("auto"))
        .credentialsProvider(credentials(properties))
        .serviceConfiguration(pathStyle())
        .build();
  }

  private static StaticCredentialsProvider credentials(R2Properties properties) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
  }

  private static S3Configuration pathStyle() {
    return S3Configuration.builder().pathStyleAccessEnabled(true).build();
  }
}
