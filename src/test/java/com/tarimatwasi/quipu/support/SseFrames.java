package com.tarimatwasi.quipu.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.google.common.base.Splitter;
import io.swagger.v3.core.util.Yaml;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Validate the actual JSON data against the pinned contract, independently of the HTTP string. */
public final class SseFrames {
  private SseFrames() {}

  public static void assertValid(String wire, String role) throws Exception {
    var mapper = Yaml.mapper();
    var specification = mapper.readTree(Files.readString(Path.of("target/contract/bff.yaml")));
    for (String frame : Splitter.on("\n\n").split(wire.replace("\r\n", "\n"))) {
      if (frame.isBlank() || frame.startsWith(":")) {
        continue;
      }
      assertThat(frame.getBytes(StandardCharsets.UTF_8).length + 2).isLessThanOrEqualTo(8192);
      String event = "";
      var data = new StringBuilder();
      for (String line : Splitter.on('\n').split(frame)) {
        if (line.startsWith("event: ")) {
          event = line.substring(7);
        }
        if (line.startsWith("data: ")) {
          if (!data.isEmpty()) {
            data.append('\n');
          }
          data.append(line.substring(6));
        }
      }
      var ref =
          specification.at(
              "/components/responses/"
                  + role
                  + "ChangeStream/content/text~1event-stream/x-sse-events/"
                  + event
                  + "/dataSchema/$ref");
      assertThat(ref.isTextual()).as("contract mapping for SSE event " + event).isTrue();
      var validator = validator(specification, ref.asText());
      var report =
          validator.validateResponse(
              "/event",
              Request.Method.GET,
              SimpleResponse.Builder.status(200)
                  .withContentType("application/json")
                  .withBody(data.toString())
                  .build());
      assertThat(report.hasErrors()).as(report.toString()).isFalse();
    }
  }

  private static OpenApiInteractionValidator validator(
      com.fasterxml.jackson.databind.JsonNode specification, String ref) throws Exception {
    var mapper = Yaml.mapper();
    var json = io.swagger.v3.core.util.Json.mapper();
    var eventSpec = specification.deepCopy();
    var paths = mapper.createObjectNode();
    paths
        .putObject("/event")
        .putObject("get")
        .putObject("responses")
        .putObject("200")
        .put("description", "Decoded SSE data")
        .putObject("content")
        .putObject("application/json")
        .putObject("schema")
        .put("$ref", ref);
    ((com.fasterxml.jackson.databind.node.ObjectNode) eventSpec).set("paths", paths);
    var servers = mapper.createArrayNode();
    servers.addObject().put("url", "/");
    ((com.fasterxml.jackson.databind.node.ObjectNode) eventSpec).set("servers", servers);
    return OpenApiInteractionValidator.createForInlineApiSpecification(
            json.writeValueAsString(eventSpec))
        .build();
  }
}
