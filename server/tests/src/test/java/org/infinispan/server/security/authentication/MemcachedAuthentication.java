package org.infinispan.server.security.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.security.BasicCallbackHandler;
import org.infinispan.commons.util.Util;
import org.infinispan.server.test.api.TestClientDriver;
import org.infinispan.server.test.core.Common;
import org.infinispan.server.test.core.tags.Security;
import org.infinispan.server.test.junit5.InfinispanServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import net.spy.memcached.ConnectionFactoryBuilder;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.auth.AuthDescriptor;
import net.spy.memcached.internal.OperationFuture;

/**
 * @since 15.0
 **/

@Security
public class MemcachedAuthentication {

   @InfinispanServer(AuthenticationIT.class)
   public static TestClientDriver SERVERS;

   private static final Provider[] SECURITY_PROVIDERS;

   static {
      // Register only the providers that matter to us
      List<Provider> providers = new ArrayList<>();
      for (String name : Arrays.asList(
            "org.wildfly.security.sasl.plain.WildFlyElytronSaslPlainProvider",
            "org.wildfly.security.sasl.digest.WildFlyElytronSaslDigestProvider",
            "org.wildfly.security.sasl.external.WildFlyElytronSaslExternalProvider",
            "org.wildfly.security.sasl.oauth2.WildFlyElytronSaslOAuth2Provider",
            "org.wildfly.security.sasl.scram.WildFlyElytronSaslScramProvider",
            "org.wildfly.security.sasl.gssapi.WildFlyElytronSaslGssapiProvider",
            "org.wildfly.security.sasl.gs2.WildFlyElytronSaslGs2Provider"
      )) {
         Provider provider = Util.getInstance(name, RemoteCacheManager.class.getClassLoader());
         providers.add(provider);
      }
      SECURITY_PROVIDERS = providers.toArray(new Provider[0]);
   }

   @ParameterizedTest
   @ArgumentsSource(Common.SaslMechsArgumentProvider.class)
   public void testMemcachedReadWrite(String mechanism) throws ExecutionException, InterruptedException, TimeoutException {
      ConnectionFactoryBuilder builder = new ConnectionFactoryBuilder();
      builder.setProtocol(mechanism.isEmpty() ? ConnectionFactoryBuilder.Protocol.TEXT : ConnectionFactoryBuilder.Protocol.BINARY);
      builder.setAuthDescriptor(new AuthDescriptor(new String[]{mechanism}, new BasicCallbackHandler("all_user", "default", "all".toCharArray()), null, null, SECURITY_PROVIDERS));
      MemcachedClient client = SERVERS.memcached().withClientConfiguration(builder).get();
      OperationFuture<Boolean> f = client.set("k" + mechanism, 0, "v");
      assertTrue(f.get(10, TimeUnit.SECONDS));
      assertEquals(client.get("k" + mechanism), "v");
   }
}
