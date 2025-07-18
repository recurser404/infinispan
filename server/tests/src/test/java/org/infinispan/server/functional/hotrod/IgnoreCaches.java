package org.infinispan.server.functional.hotrod;

import static java.util.Collections.singleton;
import static org.infinispan.query.remote.client.ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME;
import static org.infinispan.rest.helper.RestResponses.assertStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.infinispan.client.rest.RestClient;
import org.infinispan.client.rest.configuration.RestClientConfigurationBuilder;
import org.infinispan.commons.dataconversion.internal.Json;
import org.infinispan.rest.helper.RestResponses;
import org.infinispan.server.functional.ClusteredIT;
import org.infinispan.server.test.api.TestClientDriver;
import org.infinispan.server.test.junit5.InfinispanServer;
import org.junit.jupiter.api.Test;

/**
 * @since 10.0
 */
public class IgnoreCaches {

   @InfinispanServer(ClusteredIT.class)
   public static TestClientDriver SERVERS;

   @Test
   public void testIgnoreCaches() {
      RestClientConfigurationBuilder builder = new RestClientConfigurationBuilder();
      RestClient client = SERVERS.rest().withClientConfiguration(builder).create();
      String testCache = SERVERS.getMethodName();

      assertTrue(getIgnoredCaches(client).isEmpty());
      assertCacheResponse(client, testCache, 404);
      assertCacheResponse(client, PROTOBUF_METADATA_CACHE_NAME, 404);

      ignoreCache(client, testCache);
      assertEquals(singleton(testCache), getIgnoredCaches(client));
      assertCacheResponse(client, testCache, 503);
      assertCacheResponse(client, PROTOBUF_METADATA_CACHE_NAME, 404);

      ignoreCache(client, PROTOBUF_METADATA_CACHE_NAME);
      assertEquals(asSet(testCache, PROTOBUF_METADATA_CACHE_NAME), getIgnoredCaches(client));
      assertCacheResponse(client, testCache, 503);
      assertCacheResponse(client, PROTOBUF_METADATA_CACHE_NAME, 503);

      unIgnoreCache(client, testCache);
      assertEquals(singleton(PROTOBUF_METADATA_CACHE_NAME), getIgnoredCaches(client));
      assertCacheResponse(client, testCache, 404);
      assertCacheResponse(client, PROTOBUF_METADATA_CACHE_NAME, 503);

      unIgnoreCache(client, PROTOBUF_METADATA_CACHE_NAME);
      assertTrue(getIgnoredCaches(client).isEmpty());
      assertCacheResponse(client, testCache, 404);
      assertCacheResponse(client, PROTOBUF_METADATA_CACHE_NAME, 404);
   }

   private Set<String> asSet(String... elements) {
      return Arrays.stream(elements).collect(Collectors.toSet());
   }

   private void assertCacheResponse(RestClient client, String cacheName, int code) {
      assertStatus(code, client.cache(cacheName).get("whatever"));
   }

   private void unIgnoreCache(RestClient client, String cacheName) {
      assertStatus(204, client.server().unIgnoreCache(cacheName));
   }

   private void ignoreCache(RestClient client, String cacheName) {
      assertStatus(204, client.server().ignoreCache(cacheName));
   }

   private Set<String> getIgnoredCaches(RestClient client) {
      Json body = RestResponses.jsonResponseBody(client.server().listIgnoredCaches());
      return body.asJsonList().stream().map(Json::asString).collect(Collectors.toSet());
   }
}
