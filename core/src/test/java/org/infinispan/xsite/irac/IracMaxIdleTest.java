package org.infinispan.xsite.irac;

import static org.infinispan.test.TestingUtil.extractComponent;
import static org.infinispan.test.TestingUtil.replaceComponent;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.infinispan.Cache;
import org.infinispan.commands.RequestUUID;
import org.infinispan.commons.time.ControlledTimeService;
import org.infinispan.commons.time.TimeService;
import org.infinispan.configuration.cache.BackupConfiguration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.container.impl.InternalDataContainer;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.test.TestingUtil;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.xsite.AbstractMultipleSitesTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests if max-idle expiration works properly with x-site (ISPN-13057)
 *
 * @author Pedro Ruivo
 * @since 13.0
 */
@Test(groups = "functional", testName = "xsite.irac.IracMaxIdleTest")
public class IracMaxIdleTest extends AbstractMultipleSitesTest {

   private static final long MAX_IDLE = 1000; //milliseconds
   private final ControlledTimeService timeService = new ControlledTimeService();

   @Override
   protected int defaultNumberOfNodes() {
      return 1;
   }

   @Override
   protected ConfigurationBuilder defaultConfigurationForSite(int siteIndex) {
      ConfigurationBuilder builder = super.defaultConfigurationForSite(siteIndex);
      builder.expiration().reaperEnabled(false);
      builder.sites().addBackup()
            .site(siteIndex == 0 ? siteName(1) : siteName(0))
            .strategy(BackupConfiguration.BackupStrategy.ASYNC);
      return builder;
   }

   @DataProvider(name = "data")
   public Object[][] data() {
      return new Object[][]{
            {TestData.NON_TX},
            {TestData.PESSIMISTIC},
            {TestData.OPTIMISTIC}
      };
   }

   @Test(dataProvider = "data")
   public void testMaxIdle(TestData testData) {
      final String cacheName = createCaches(testData);
      List<ManualIracManager> iracManagers = caches(0, cacheName).stream()
            .map(ManualIracManager::wrapCache)
            .peek(m -> m.disable(ManualIracManager.DisableMode.DROP)) // reset state
            .toList();
      final String key = createKeyOrValue(testData, "key");
      final String value = createKeyOrValue(testData, "value");

      cache(0, 0, cacheName).put(key, value, -1, TimeUnit.MILLISECONDS, MAX_IDLE, TimeUnit.MILLISECONDS);

      eventuallyAssertInAllSitesAndCaches(cacheName, c -> Objects.equals(value, c.get(key)));

      // block xsite replication (remove expired).
      // the touch command is not blocked
      iracManagers.forEach(ManualIracManager::enable);

      timeService.advance(MAX_IDLE + 1);

      // get should trigger the expiration
      assertNull(cache(0, 0, cacheName).get(key));

      // one of them should have the key there
      assertTrue(iracManagers.stream().anyMatch(ManualIracManager::hasPendingKeys));

      // let the key go
      iracManagers.forEach(ManualIracManager::sendKeys);

      // eventually it should go
      eventually(() -> iracManagers.stream().noneMatch(ManualIracManager::hasPendingKeys));
      eventually(() -> iracManagers.stream().allMatch(ManualIracManager::isEmpty));

      assertNoKeyInDataContainer(1, cacheName, key);
      assertNoKeyInDataContainer(0, cacheName, key);
      assertNoDataLeak(cacheName);
   }

   @Test
   public void testConcurrentWriteWithExpiration() throws Throwable {
      final var cacheName = "concurrent";

      // create cache
      for (int i = 0; i < defaultNumberOfSites(); ++i) {
         defineInSite(site(i), cacheName, defaultConfigurationForSite(i).build());
         site(i).waitForClusterToForm(cacheName);
      }

      // register irac manager
      var iracManagers = caches(0, cacheName).stream()
            .map(cache -> {
               IracManager iracManager = TestingUtil.extractComponent(cache, IracManager.class);
               if (iracManager instanceof SendExpirationAfterWriteIracManager) {
                  return (SendExpirationAfterWriteIracManager) iracManager;
               }
               return TestingUtil.wrapComponent(cache, IracManager.class, SendExpirationAfterWriteIracManager::new);
            })
            .peek(m -> m.disable(ManualIracManager.DisableMode.DROP)) // reset state
            .toList();

      final var key = "c_key";
      final var value = "c_value";
      final var value2 = "c_value_2";

      cache(0, 0, cacheName).put(key, value, -1, TimeUnit.MILLISECONDS, MAX_IDLE, TimeUnit.MILLISECONDS);

      eventuallyAssertInAllSitesAndCaches(cacheName, c -> Objects.equals(value, c.get(key)));

      // block xsite replication (remove expired).
      // the touch command is not blocked
      iracManagers.forEach(ManualIracManager::enable);

      timeService.advance(MAX_IDLE + 1);

      // get should trigger the expiration
      assertNull(cache(0, 0, cacheName).get(key));

      // one of them should have the key there
      assertTrue(iracManagers.stream().anyMatch(ManualIracManager::hasPendingKeys));

      // Test concurrent write
      // Tiggers the cross-site expiration request and block the cross-site write command in IracManager.
      cache(0, 0, cacheName).put(key, value2);

      // if the bug occurs, the entry is never remove from IracManager and this fails.
      eventually(() -> iracManagers.stream().allMatch(ManualIracManager::isEmpty), 10, TimeUnit.SECONDS);

      iracManagers.forEach(m -> m.disable(ManualIracManager.DisableMode.SEND));

      eventuallyAssertInAllSitesAndCaches(cacheName, c -> Objects.equals(value2, c.get(key)));
      assertTrue(iracManagers.stream().allMatch(ManualIracManager::isEmpty));
   }

   private static String createKeyOrValue(TestData testData, String prefix) {
      return switch (testData) {
         case NON_TX -> prefix + "_ntx_";
         case PESSIMISTIC -> prefix + "_pes_";
         case OPTIMISTIC -> prefix + "_opt_";
      };
   }

   private String createCaches(TestData testData) {
      String cacheName;
      LockingMode lockingMode;
      switch (testData) {
         case NON_TX:
            // default cache is fine
            return null;
         case PESSIMISTIC:
            cacheName = "pes_cache";
            lockingMode = LockingMode.PESSIMISTIC;
            break;
         case OPTIMISTIC:
            cacheName = "opt_cache";
            lockingMode = LockingMode.OPTIMISTIC;
            break;
         default:
            throw new IllegalStateException(String.valueOf(testData));
      }
      for (int i = 0; i < defaultNumberOfSites(); ++i) {
         defineInSite(site(i), cacheName, defaultConfigurationForSite(i)
               .transaction()
               .transactionMode(TransactionMode.TRANSACTIONAL)
               .lockingMode(lockingMode)
               .build());
         site(i).waitForClusterToForm(cacheName);
      }
      return cacheName;
   }

   private void assertNoKeyInDataContainer(int siteIndex, String cacheName, String key) {
      for (Cache<String, String> c : this.<String, String>caches(siteIndex, cacheName)) {
         assertNull(internalDataContainer(c).peek(key));
      }
   }

   private InternalDataContainer<String, String> internalDataContainer(Cache<String, String> c) {
      //noinspection unchecked
      return extractComponent(c, InternalDataContainer.class);
   }

   @Override
   protected void afterSitesCreated() {
      super.afterSitesCreated();
      for (int i = 0; i < defaultNumberOfSites(); ++i) {
         site(i).cacheManagers().forEach(cm -> replaceComponent(cm, TimeService.class, timeService, true));
      }
   }

   public enum TestData {
      NON_TX,
      PESSIMISTIC,
      OPTIMISTIC
   }

   @Scope(Scopes.NAMED_CACHE)
   public static class SendExpirationAfterWriteIracManager extends ManualIracManager {

      SendExpirationAfterWriteIracManager(IracManager actual) {
         super(actual);
      }

      @Override
      public void trackUpdatedKey(int segment, Object key, RequestUUID owner) {
         if (enabled) {
            var existing = pendingKeys.get(key);
            if (existing != null && existing.isExpiration()) {
               actual.trackExpiredKey(existing.getSegment(), existing.getKey(), existing.getOwner());
            }
            pendingKeys.put(key, new PendingKeyRequest(key, owner, segment, false));
         } else {
            super.trackUpdatedKey(segment, key, owner);
         }
      }
   }
}
