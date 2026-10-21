package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Data source for the isolated Compose integration database; credentials are test-only. */
final class PostgresTestDataSource implements DataSource {
  private final String url =
      System.getenv()
          .getOrDefault("EFS_TEST_JDBC_URL", "jdbc:postgresql://localhost:54329/efs_test");
  private final String user = System.getenv().getOrDefault("EFS_TEST_DB_USER", "efs_test");
  private final String password =
      System.getenv().getOrDefault("EFS_TEST_DB_PASSWORD", "efs_test_only");

  @Override
  public Connection getConnection() throws SQLException {
    return DriverManager.getConnection(url, user, password);
  }

  @Override
  public Connection getConnection(String username, String userPassword) throws SQLException {
    return DriverManager.getConnection(url, username, userPassword);
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {}

  @Override
  public void setLoginTimeout(int seconds) {
    DriverManager.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("efs-test-datasource");
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    if (type.isInstance(this)) {
      return type.cast(this);
    }
    throw new SQLException("not a wrapper for " + type.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return type.isInstance(this);
  }
}
