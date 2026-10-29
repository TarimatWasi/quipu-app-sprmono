package com.tarimatwasi.quipu.shared.config;

import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * TAR-214: the connection Flyway migrates through and the role the migrations grant ({@code
 * app.migration.*}). All empty (local, the tests, and every deployment until the application role
 * exists): Flyway and the application share {@code spring.datasource}, as before. With {@code url}:
 * Flyway migrates as the owner of the tables and the application connects through {@code
 * spring.datasource} as {@code appRole}, which does not own them, so it cannot TRUNCATE the
 * evidence nor drop its triggers.
 *
 * @param url JDBC URL of the owner role; it may carry the credentials, as DATABASE_URL does
 * @param user owner role, when the URL does not carry it
 * @param password password of the owner role, when the URL does not carry it
 * @param appRole role the migrations grant the privileges of the application to; required with
 *     {@code url}. Alone, it is granted through the shared connection, before switching to it.
 */
@ConfigurationProperties("app.migration")
@Validated
record MigrationProperties(
    @DefaultValue("") String url,
    @DefaultValue("") String user,
    @DefaultValue("") String password,
    @DefaultValue("") String appRole) {

  /**
   * An unquoted PostgreSQL identifier of at most 63 characters: the migration receives it as a
   * Flyway placeholder inside a literal, so nothing else may reach the SQL.
   */
  private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

  MigrationProperties {
    url = clean(url);
    user = clean(user);
    password = clean(password);
    appRole = clean(appRole);
    if (url.isEmpty() && (!user.isEmpty() || !password.isEmpty())) {
      throw new IllegalStateException(
          "app.migration.user and app.migration.password need app.migration.url");
    }
    if (!url.isEmpty() && appRole.isEmpty()) {
      throw new IllegalStateException(
          "app.migration.url needs app.migration.app-role: the application role would get no"
              + " privileges");
    }
    if (!appRole.isEmpty() && !ROLE_NAME.matcher(appRole).matches()) {
      throw new IllegalStateException(
          "app.migration.app-role must be an unquoted PostgreSQL role name (a-z, 0-9, _)");
    }
  }

  /** Whether Flyway migrates through a connection of its own. */
  boolean separated() {
    return !url.isEmpty();
  }

  /** The owner connection of Flyway; only when {@link #separated()}. */
  DataSource ownerDataSource() {
    return DataSourceBuilder.create()
        .type(SimpleDriverDataSource.class)
        .url(url)
        .username(user.isEmpty() ? null : user)
        .password(password.isEmpty() ? null : password)
        .build();
  }

  /** Neither the URL nor the password: either may carry the credentials of the owner. */
  @Override
  public String toString() {
    return "MigrationProperties[separated=" + separated() + ", appRole=" + appRole + "]";
  }

  private static String clean(String value) {
    return StringUtils.hasText(value) ? value.strip() : "";
  }
}
