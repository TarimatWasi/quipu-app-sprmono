package com.tarimatwasi.quipu.shared.adapter.out.storage;

import java.net.URI;
import java.time.Clock;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class R2PingService {

  private final R2Properties properties;
  private final Clock clock;

  public R2PingService(R2Properties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public String ping() {
    String key = "dev/ping-" + clock.millis() + ".txt";
    // Client built per call so missing/bad config reports FAIL instead of crashing context startup.
    try (S3Client s3Client =
        S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint()))
            .region(Region.of("auto"))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        properties.accessKeyId(), properties.secretAccessKey())))
            .build()) {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(properties.bucketName()).key(key).build(),
          RequestBody.fromString("connectivity check"));
      s3Client.deleteObject(
          DeleteObjectRequest.builder().bucket(properties.bucketName()).key(key).build());
      return "OK";
    } catch (SdkException | IllegalArgumentException e) {
      return "FAIL: " + e.getMessage();
    }
  }
}
