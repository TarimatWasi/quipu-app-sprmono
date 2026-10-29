package com.tarimatwasi.quipu.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * TAR-214: app.migration.* is all or nothing. Without it Flyway keeps the connection of the
 * application; with it Flyway migrates through its own connection as the owner and grants the
 * application role.
 */
class MigrationPropertiesTest {

  private static final String URL = "jdbc:postgresql://localhost:5432/quipu";
  private static final String SECRET = "not-a-real-password";

  private final DataSource applicationDataSource = new SimpleDriverDataSource();

  @Test
  void withoutSeparationFlywayKeepsTheApplicationConnectionAndAnEmptyRole() {
    var configuration = customize(new MigrationProperties("", "", "", ""));

    assertThat(configuration.getDataSource()).isSameAs(applicationDataSource);
    assertThat(configuration.getPlaceholders()).containsEntry("app_role", "");
  }

  @Test
  void aRoleAloneIsGrantedThroughTheApplicationConnection() {
    var configuration = customize(new MigrationProperties("", "", "", "quipu_app"));

    assertThat(configuration.getDataSource()).isSameAs(applicationDataSource);
    assertThat(configuration.getPlaceholders()).containsEntry("app_role", "quipu_app");
  }

  @Test
  void withSeparationFlywayMigratesThroughTheOwnerConnection() {
    var configuration = customize(new MigrationProperties(URL, "quipu_owner", SECRET, "quipu_app"));

    assertThat(configuration.getDataSource())
        .isInstanceOfSatisfying(
            SimpleDriverDataSource.class,
            owner -> {
              assertThat(owner.getUrl()).isEqualTo(URL);
              assertThat(owner.getUsername()).isEqualTo("quipu_owner");
              assertThat(owner.getPassword()).isEqualTo(SECRET);
            });
    assertThat(configuration.getPlaceholders()).containsEntry("app_role", "quipu_app");
  }

  @Test
  void credentialsInsideTheUrlNeedNoUserNorPassword() {
    var configuration = customize(new MigrationProperties(URL, "", "", "quipu_app"));

    assertThat(configuration.getDataSource())
        .isInstanceOfSatisfying(
            SimpleDriverDataSource.class,
            owner -> {
              assertThat(owner.getUrl()).isEqualTo(URL);
              assertThat(owner.getUsername()).isNull();
              assertThat(owner.getPassword()).isNull();
            });
  }

  @Test
  void anOwnerConnectionWithoutAnApplicationRoleIsRefused() {
    assertThatThrownBy(() -> new MigrationProperties(URL, "quipu_owner", SECRET, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.migration.app-role")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void aUserOrPasswordWithoutTheUrlIsRefused() {
    assertThatThrownBy(() -> new MigrationProperties("", "quipu_owner", "", "quipu_app"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.migration.url");
    assertThatThrownBy(() -> new MigrationProperties("", "", SECRET, "quipu_app"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.migration.url")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void aRoleNameThatIsNotAPlainIdentifierIsRefused() {
    for (String role :
        new String[] {"quipu'; DROP TABLE users; --", "1quipu", "quipu app", "a".repeat(64)}) {
      assertThatThrownBy(() -> new MigrationProperties("", "", "", role))
          .as(role)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("app.migration.app-role")
          .hasMessageNotContaining(role);
    }
  }

  @Test
  void theTextOfThePropertiesHidesThePassword() {
    var properties = new MigrationProperties(URL, "quipu_owner", SECRET, "quipu_app");

    assertThat(properties.toString()).doesNotContain(SECRET).doesNotContain(URL);
  }

  private FluentConfiguration customize(MigrationProperties properties) {
    var configuration = new FluentConfiguration().dataSource(applicationDataSource);
    new FlywayConfig().migrationConnection(properties).customize(configuration);
    return configuration;
  }
}
