package org.infinispan.persistence;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.infinispan.AdvancedCache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.marshall.WrappedByteArray;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.IsolationLevel;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.encoding.DataConversion;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.dummy.DummyInMemoryStore;
import org.infinispan.persistence.dummy.DummyInMemoryStoreConfigurationBuilder;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.transaction.lookup.EmbeddedTransactionManagerLookup;
import org.testng.annotations.Test;

/**
 * Test if keys are properly passivated and reloaded in local mode (to ensure fix for ISPN-2712 did no break local mode).
 *
 * @author anistor@redhat.com
 * @since 5.2
 */
@Test(groups = "functional", testName = "persistence.LocalModePassivationTest")
@CleanupAfterMethod
public class LocalModePassivationTest extends SingleCacheManagerTest {

   private final boolean passivationEnabled;

   protected LocalModePassivationTest() {
      passivationEnabled = true;
   }

   protected LocalModePassivationTest(boolean passivationEnabled) {
      this.passivationEnabled = passivationEnabled;
   }

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      ConfigurationBuilder builder = getDefaultClusteredCacheConfig(CacheMode.LOCAL, true, true);
      builder.transaction().transactionMode(TransactionMode.TRANSACTIONAL).lockingMode(LockingMode.PESSIMISTIC)
            .transactionManagerLookup(new EmbeddedTransactionManagerLookup())
            .memory().storage(StorageType.HEAP).maxCount(150)
            .encoding().mediaType(MediaType.APPLICATION_PROTOSTREAM)
            .locking().useLockStriping(false).isolationLevel(IsolationLevel.READ_COMMITTED)
            .persistence()
               .passivation(passivationEnabled)
               .addStore(DummyInMemoryStoreConfigurationBuilder.class)
                  .storeName(getClass().getName())
               .fetchPersistentState(true)
               .ignoreModifications(false)
               .preload(false)
               .purgeOnStartup(false);

      return TestCacheManagerFactory.createCacheManager(builder);
   }

   public void testStoreAndLoad() throws Exception {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache().put(i, i);
      }

      int keysInDataContainer = cache().getAdvancedCache().getDataContainer().size();

      assertTrue(keysInDataContainer != numKeys); // some keys got evicted

      DummyInMemoryStore store = TestingUtil.getFirstStore(cache());
      long keysInCacheStore = store.size();

      if (passivationEnabled) {
         assertEquals(numKeys, keysInDataContainer + keysInCacheStore);
      } else {
         assertEquals(numKeys, keysInCacheStore);
      }

      // check if keys survive restart
      cache().stop();
      cache().start();

      store = TestingUtil.getFirstStore(cache());
      assertEquals(numKeys, store.size());

      for (int i = 0; i < numKeys; i++) {
         assertEquals(i, cache().get(i));
      }
   }

   public void testSizeWithEvictedEntries() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }
      assertFalse("Data Container should not have all keys", numKeys == cache.getAdvancedCache().getDataContainer().size());
      assertEquals(numKeys, cache.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).size());
   }

   public void testSizeWithEvictedEntriesAndFlags() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }
      assertFalse("Data Container should not have all keys", numKeys == cache.getAdvancedCache().getDataContainer().size());
      assertEquals(cache.getAdvancedCache().getDataContainer().size(), cache.getAdvancedCache().withFlags(Flag.SKIP_CACHE_LOAD).size());
      // Skip cache store only prevents writes not reads
      assertEquals(300, cache.getAdvancedCache().withFlags(Flag.SKIP_CACHE_STORE).size());
   }

   public void testKeySetWithEvictedEntries() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }

      assertFalse("Data Container should not have all keys", numKeys == cache.getAdvancedCache().getDataContainer().size());
      Set<Object> keySet = cache.keySet();
      for (int i = 0; i < numKeys; i++) {
         assertTrue("Key: " + i + " was not found!", keySet.contains(i));
      }
   }

   public void testKeySetWithEvictedEntriesAndFlags() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }

      AdvancedCache<Object, Object> flagCache = cache.getAdvancedCache().withFlags(Flag.SKIP_CACHE_LOAD);
      DataContainer<Object, Object> dc = flagCache.getDataContainer();
      assertFalse("Data Container should not have all keys", numKeys == dc.size());
      Set<Object> keySet = flagCache.keySet();
      assertEquals(dc.size(), keySet.size());
      DataConversion conversion = flagCache.getValueDataConversion();
      for (InternalCacheEntry<Object, Object> entry : dc) {
         Object key = entry.getKey();
         assertTrue("Key: " + key + " was not found!", keySet.contains(conversion.fromStorage(key)));
      }
   }

   public void testEntrySetWithEvictedEntries() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }

      assertFalse("Data Container should not have all keys", numKeys == cache.getAdvancedCache().getDataContainer().size());
      Set<Map.Entry<Object, Object>> entrySet = cache.entrySet();
      assertEquals(numKeys, entrySet.size());

      Map<Object, Object> map = new HashMap<>(entrySet.size());
      for (Map.Entry<Object, Object> entry : entrySet) {
         map.put(entry.getKey(), entry.getValue());
      }

      for (int i = 0; i < numKeys; i++) {
         assertEquals("Key/Value mismatch!", i, map.get(i));
      }
   }

   public void testEntrySetWithEvictedEntriesAndFlags() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }

      AdvancedCache<Object, Object> flagCache = cache.getAdvancedCache().withFlags(Flag.SKIP_CACHE_LOAD);
      DataContainer<Object, Object> dc = flagCache.getDataContainer();
      assertFalse("Data Container should not have all keys", numKeys == dc.size());
      Set<Map.Entry<Object, Object>> entrySet = flagCache.entrySet();
      assertEquals(dc.size(), entrySet.size());

      DataConversion keyDataConversion = flagCache.getAdvancedCache().getKeyDataConversion();
      DataConversion valueDataConversion = flagCache.getAdvancedCache().getValueDataConversion();
      Map<WrappedByteArray, WrappedByteArray> map = new HashMap<>(entrySet.size());
      for (Map.Entry<Object, Object> entry : entrySet) {
         WrappedByteArray storedKey = (WrappedByteArray) keyDataConversion.toStorage(entry.getKey());
         WrappedByteArray storedValue = (WrappedByteArray) valueDataConversion.toStorage(entry.getValue());
         map.put(storedKey, storedValue);
      }

      for (InternalCacheEntry entry : dc) {
         assertEquals("Key/Value mismatch!", entry.getValue(), map.get(entry.getKey()));
      }
   }

   public void testValuesWithEvictedEntries() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }

      assertFalse("Data Container should not have all keys", numKeys == cache.getAdvancedCache().getDataContainer().size());
      Collection<Object> values = cache.values();
      for (int i = 0; i < numKeys; i++) {
         assertTrue("Value: " + i + " was not found!", values.contains(i));
      }
   }

   public void testValuesWithEvictedEntriesAndFlags() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache.put(i, i);
      }

      AdvancedCache<Object, Object> flagCache = cache.getAdvancedCache().withFlags(Flag.SKIP_CACHE_LOAD);
      DataContainer<Object, Object> dc = flagCache.getDataContainer();
      assertFalse("Data Container should not have all keys", numKeys == dc.size());
      Collection<Object> values = flagCache.values();
      assertEquals(dc.size(), values.size());

      for (InternalCacheEntry<Object, Object> entry : dc) {
         Object dcValue = entry.getValue();
         DataConversion valueDataConversion = flagCache.getValueDataConversion();
         assertTrue("Value: " + dcValue + " was not found!", values.contains(valueDataConversion.fromStorage(dcValue)));
      }
   }

   public void testStoreAndLoadWithGetEntry() {
      final int numKeys = 300;
      for (int i = 0; i < numKeys; i++) {
         cache().put(i, i);
      }

      int keysInDataContainer = cache().getAdvancedCache().getDataContainer().size();

      assertTrue(keysInDataContainer != numKeys); // some keys got evicted

      DummyInMemoryStore dims = TestingUtil.getFirstStore(cache());
      long keysInCacheStore = dims.size();

      if (passivationEnabled) {
         assertEquals(numKeys, keysInDataContainer + keysInCacheStore);
      } else {
         assertEquals(numKeys, keysInCacheStore);
      }

      // check if keys survive restart
      cache().stop();
      cache().start();

      dims = TestingUtil.getFirstStore(cache());
      assertEquals(numKeys, dims.size());

      for (int i = 0; i < numKeys; i++) {
         assertEquals(i, cache.getAdvancedCache().getCacheEntry(i).getValue());
      }
   }
}
