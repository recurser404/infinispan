package org.infinispan.server.persistence;

import static org.infinispan.commons.test.Eventually.eventually;
import static org.infinispan.server.test.core.TestSystemPropertyNames.INFINISPAN_TEST_CONTAINER_DATABASE_PROPERTIES;
import static org.infinispan.server.test.core.TestSystemPropertyNames.INFINISPAN_TEST_CONTAINER_DATABASE_TYPES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.infinispan.commons.util.Util;
import org.infinispan.server.test.core.persistence.ContainerDatabase;
import org.infinispan.server.test.core.tags.Database;
import org.jgroups.Event;
import org.jgroups.JChannel;
import org.jgroups.protocols.FD_ALL3;
import org.jgroups.protocols.FRAG4;
import org.jgroups.protocols.JDBC_PING2;
import org.jgroups.protocols.MERGE3;
import org.jgroups.protocols.MFC;
import org.jgroups.protocols.PingData;
import org.jgroups.protocols.RED;
import org.jgroups.protocols.TCP;
import org.jgroups.protocols.UFC;
import org.jgroups.protocols.UNICAST3;
import org.jgroups.protocols.VERIFY_SUSPECT2;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

/**
 * JChannel based JDBC_PING2 tests. If an Infinispan server is required for a test, {@link JdbcPing2IT} should be
 * used instead.
 */
@Database
public class JGroupsJdbcPing2IT {

   public static class DatabaseProvider implements ArgumentsProvider {
      @Override
      public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
         String property = System.getProperty(INFINISPAN_TEST_CONTAINER_DATABASE_TYPES);

         return Arrays
               .stream(property != null ? property.split(",") : PersistenceIT.DEFAULT_DATABASES)
               .map(Arguments::of);
      }
   }

   @ParameterizedTest
   @ArgumentsSource(DatabaseProvider.class)
   @Execution(ExecutionMode.SAME_THREAD)
   public void testDBConnectionLost(String databaseType) throws Exception {
      ContainerDatabase db = initDatabase(databaseType);
      db.start();

      var clusterName = "test";
      var datasource = new DummyDataSource(db.jdbcUrl(), db.username(), db.password());
      try (var c1 = createChannel(databaseType, 7800, datasource);
           var c2 = createChannel(databaseType, 7801, datasource)) {
         c1.connect(clusterName);
         c2.connect(clusterName);

         eventually(() ->c1.view().size() == 2);
         eventually(() ->c2.view().size() == 2);

         db.stop(false);
         CountDownLatch reqLatch = new CountDownLatch(1);
         CountDownLatch successLatch = new CountDownLatch(2);
         c1.getProtocolStack().insertProtocol(new DiscoveryListener(reqLatch, successLatch), ProtocolStack.Position.ABOVE, JDBC_PING2.class);
         assertTrue(reqLatch.await(10, TimeUnit.MINUTES));
         assertEquals(2, successLatch.getCount());
         db.restart();
         assertTrue(successLatch.await(10, TimeUnit.MINUTES));
      } finally {
         db.stop();
      }
   }

   private ContainerDatabase initDatabase(String databaseType) {
      var props = properties(databaseType);

      // The test assumes TestContainer driven deployment and a volume mount
      Assumptions.assumeTrue("CONTAINER".equals(props.get("database.mode")));
      Assumptions.assumeTrue(props.get("database.container.volumeMount") != null);

      // Ensure that a volume is created so that tables and their content survive container recreation
      props.put(ContainerDatabase.DB_PREFIX + "volume", "true");
      return new ContainerDatabase(databaseType, props);
   }

   private Properties properties(String databaseType) {
      String property = System.getProperty(INFINISPAN_TEST_CONTAINER_DATABASE_PROPERTIES);
      try (InputStream inputStream = property != null ? Files.newInputStream(Paths.get(property).resolve(databaseType + ".properties")) : getClass().getResourceAsStream(String.format("/database/%s.properties", databaseType))) {
         Properties properties = new Properties();
         properties.load(inputStream);
         return properties;
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   private JChannel createChannel(String dbType, int port, DataSource dataSource) throws Exception {
      var jdbcPing2 = new JDBC_PING2().setDataSource(dataSource);
      if ("oracle".equals(dbType)) {
         // JDBC_PING2 default SQL will not work with any Oracle version due to the use of the reserved value "cluster" as a column name.
         // JDBC_PING2 default SQL won't work with Oracle < 23c as it uses a boolean type which does not exist. Instead, we must use NUMBER(1).
         jdbcPing2.setInitializeSql("CREATE TABLE jgroups (address varchar(200) NOT NULL, name varchar(200), cluster_name varchar(200) NOT NULL, ip varchar(200) NOT NULL, coord NUMBER(1), PRIMARY KEY (address) )")
               .setClearSql("DELETE from jgroups WHERE cluster_name=?")
               .setSelectAllPingdataSql("SELECT address, name, ip, coord FROM jgroups WHERE cluster_name=?");
      } else if ("mssql".equals(dbType)) {
         // JDBC_PING2 default SQL won't work with SQL Server as there's no boolean field, instead we must use BIT
         jdbcPing2.setInitializeSql("CREATE TABLE jgroups (address varchar(200) NOT NULL, name varchar(200), cluster varchar(200) NOT NULL, ip varchar(200) NOT NULL, coord BIT, PRIMARY KEY (address) )");
      }

      return new JChannel(
            new TCP().enableSuspectEvents(true).setBindAddr(InetAddress.getLocalHost()).setBindPort(port),
            new RED(),
            jdbcPing2,
            new MERGE3().setMinInterval(1000).setMaxInterval(30000),
            new FD_ALL3(),
            new VERIFY_SUSPECT2().setTimeout(1000),
            new NAKACK2(),
            new UNICAST3(),
            new STABLE(),
            new GMS(),
            new UFC(),
            new MFC(),
            new FRAG4()
      );
   }

   static class DiscoveryListener extends Protocol {

      final CountDownLatch reqLatch;
      final CountDownLatch successLatch;

      public DiscoveryListener(CountDownLatch reqLatch, CountDownLatch successLatch) {
         this.reqLatch = reqLatch;
         this.successLatch = successLatch;
      }

      @Override
      public Object down(Event evt) {
         if (evt.getType() == Event.FIND_MBRS_ASYNC) {
            reqLatch.countDown();
            Consumer<PingData> callbackWrapper = p -> {
               // The reqLatch should have been satisfied before the DB was restarted and PingData responses received
               assert reqLatch.getCount() == 0;
               successLatch.countDown();
               Consumer<PingData> discovery_rsp_callback = evt.getArg();
               discovery_rsp_callback.accept(p);
            };
            return down_prot.down(new Event(Event.FIND_MBRS_ASYNC, callbackWrapper));
         }
         return down_prot.down(evt);
      }
   }

   record DummyDataSource(String connectionUrl, String username, String password) implements DataSource {


      @Override
      public Connection getConnection() throws SQLException {
         return getConnection(username, password);
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
         return ShimDriverManager.getConnection(connectionUrl, username, password, Util.getClassLoaders(null));
      }

      @Override
      public PrintWriter getLogWriter() {
         throw new IllegalStateException("This should not be called!");
      }

      @Override
      public void setLogWriter(PrintWriter out) {
         throw new IllegalStateException("This should not be called!");
      }

      @Override
      public void setLoginTimeout(int seconds) {
         throw new IllegalStateException("This should not be called!");
      }

      @Override
      public int getLoginTimeout() {
         throw new IllegalStateException("This should not be called!");
      }

      @Override
      public <T> T unwrap(Class<T> iface) {
         throw new IllegalStateException("This should not be called!");
      }

      @Override
      public boolean isWrapperFor(Class<?> iface) {
         throw new IllegalStateException("This should not be called!");
      }

      public Logger getParentLogger() {
         throw new IllegalStateException("This should not be called!");
      }
   }

   /**
    * Wrapper around the {@link DriverManager}.
    *
    * <p>
    * The original {@link DriverManager#getDriver(String)} is caller sensitive, and does not allow a custom
    * {@link ClassLoader} as argument to load the {@link Driver}. This causes the method to fail with a
    * {@link SQLException}, because even though the appropriate driver is found, a check with the class loader is performed.
    * </p>
    *
    * @since 16.0
    */
   private static final class ShimDriverManager {

      /**
       * Attempts to locate the {@link Driver} that accepts the given URL.
       *
       * <p>
       * This method accept custom {@link ClassLoader} to load the {@link Driver}. First, it will delegate the call to
       * {@link DriverManager#getDriver(String)}, and if it fails, it will proceed to load the driver through the
       * {@link ServiceLoader#load(Class, ClassLoader)} interface.
       * </p>
       *
       * @param url a database URL of the form
       *      *     <code>jdbc:<em>subprotocol</em>:<em>subname</em></code>
       * @param cls the class loaders to load the {@link Driver} instance.
       * @return A {@link Driver} capable of understanding the given URL.
       * @throws SQLException If a {@link Driver} is not found.
       */
      public static Driver getDriver(String url, ClassLoader[] cls) throws SQLException {
         SQLException original;
         try {
            return DriverManager.getDriver(url);
         } catch (SQLException e) {
            original = e;
         }

         for (ClassLoader loader : cls) {
            Optional<ServiceLoader.Provider<Driver>> o = ServiceLoader.load(java.sql.Driver.class, loader).stream()
                  .filter(d -> acceptUrl(d.get(), url))
                  .findFirst();

            if (o.isEmpty()) continue;

            Driver driver = o.map(ServiceLoader.Provider::get).orElseThrow();
            // Register the driver again so subsequent invocations should succeed without using the ServiceLoader.
            DriverManager.registerDriver(new Driver() {
               @Override
               public Connection connect(String url, Properties info) throws SQLException {
                  return driver.connect(url, info);
               }

               @Override
               public boolean acceptsURL(String url) throws SQLException {
                  return driver.acceptsURL(url);
               }

               @Override
               public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
                  return driver.getPropertyInfo(url, info);
               }

               @Override
               public int getMajorVersion() {
                  return driver.getMajorVersion();
               }

               @Override
               public int getMinorVersion() {
                  return driver.getMinorVersion();
               }

               @Override
               public boolean jdbcCompliant() {
                  return driver.jdbcCompliant();
               }

               @Override
               public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                  return driver.getParentLogger();
               }
            });
            return driver;
         }

         throw new SQLException("No suitable driver", "08001", original);
      }

      public static Connection getConnection(String url, String user, String password, ClassLoader[] cls) throws SQLException {
         try {
            return DriverManager.getConnection(url, user, password);
         } catch (SQLException ignore) { }

         Properties info = new Properties();
         if (user != null)
            info.put("user", user);

         if (password != null)
            info.put("password", password);

         Driver driver = getDriver(url, cls);
         return driver.connect(url, info);
      }

      private static boolean acceptUrl(Driver driver, String url) {
         try {
            return driver.acceptsURL(url);
         } catch (SQLException e) {
            return false;
         }
      }
   }
}
