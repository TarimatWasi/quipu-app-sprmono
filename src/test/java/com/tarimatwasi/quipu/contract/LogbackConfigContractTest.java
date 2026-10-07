package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ADR-F5 (TAR-139), BE-SPR-OBS-06: the logs leave for Grafana Cloud only from dev and prod and
 * never below INFO (dev logs the application at DEBUG, and that must stay on the console).
 */
class LogbackConfigContractTest {

  private static final String OTEL_APPENDER =
      "io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender";

  private static Document load() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    try (var in = new ClassPathResource("logback-spring.xml").getInputStream()) {
      return factory.newDocumentBuilder().parse(in);
    } catch (IOException e) {
      throw new AssertionError("logback-spring.xml is missing", e);
    }
  }

  private static Element first(Document doc, String tag) {
    NodeList nodes = doc.getElementsByTagName(tag);
    assertThat(nodes.getLength()).as(tag).isGreaterThan(0);
    return (Element) nodes.item(0);
  }

  @Test
  void otelAppender_dropsEverythingBelowInfo() throws Exception {
    var doc = load();
    var appenders = doc.getElementsByTagName("appender");
    Element otel = null;
    for (int i = 0; i < appenders.getLength(); i++) {
      var appender = (Element) appenders.item(i);
      if (OTEL_APPENDER.equals(appender.getAttribute("class"))) {
        otel = appender;
      }
    }
    assertThat(otel).as("OpenTelemetryAppender").isNotNull();

    var filter = (Element) otel.getElementsByTagName("filter").item(0);
    assertThat(filter.getAttribute("class"))
        .isEqualTo("ch.qos.logback.classic.filter.ThresholdFilter");
    assertThat(filter.getElementsByTagName("level").item(0).getTextContent().trim())
        .isEqualTo("INFO");
  }

  @Test
  void otelAppender_isAttachedOnlyInDevAndProd() throws Exception {
    var doc = load();
    var profile = first(doc, "springProfile");

    assertThat(profile.getAttribute("name")).isEqualTo("dev | prod");
    var refs = profile.getElementsByTagName("appender-ref");
    var names = new java.util.ArrayList<String>();
    for (int i = 0; i < refs.getLength(); i++) {
      names.add(((Element) refs.item(i)).getAttribute("ref"));
    }
    assertThat(names).containsExactly("OTEL");
  }
}
