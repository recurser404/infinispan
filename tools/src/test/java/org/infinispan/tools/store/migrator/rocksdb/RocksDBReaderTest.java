package org.infinispan.tools.store.migrator.rocksdb;

import static org.infinispan.tools.store.migrator.Element.LOCATION;
import static org.infinispan.tools.store.migrator.Element.SOURCE;
import static org.infinispan.tools.store.migrator.Element.TYPE;
import static org.infinispan.tools.store.migrator.TestUtil.propKey;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import org.infinispan.commons.util.FileLookup;
import org.infinispan.commons.util.FileLookupFactory;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.persistence.rocksdb.configuration.RocksDBStoreConfigurationBuilder;
import org.infinispan.tools.store.migrator.AbstractReaderTest;
import org.infinispan.tools.store.migrator.Element;
import org.infinispan.tools.store.migrator.StoreType;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@Test(testName = "tools.store.migrator.rocksdb.RocksDBReaderTest", groups = "functional")
public class RocksDBReaderTest extends AbstractReaderTest {

   private String getSourceDir() {
      return String.format("target/test-classes/infinispan%d/leveldbstore/", majorVersion);
   }

   private String getTargetDirectory() {
      return String.format("%s/target/%d/", getSourceDir(), targetSegments);
   }

   @Factory
   public Object[] factory() {
      return new Object[] {
            new RocksDBReaderTest(),
            new RocksDBReaderTest().targetSegments(59),
            new RocksDBReaderTest().majorVersion(9),
            new RocksDBReaderTest().majorVersion(9).targetSegments(59),
      };
   }

   public ConfigurationBuilder getTargetCacheConfig() {
      ConfigurationBuilder builder = super.getTargetCacheConfig();
      String targetDir = getTargetDirectory();
      builder.persistence()
            .addStore(RocksDBStoreConfigurationBuilder.class).location(targetDir).expiredLocation(targetDir + "expired")
            .preload(true).ignoreModifications(true).segmented(targetSegments > 0);
      return builder;
   }

   @Override
   protected void configureStoreProperties(Properties properties, Element type) {
      super.configureStoreProperties(properties, type);
      properties.put(propKey(type, TYPE), StoreType.ROCKSDB.toString());
      properties.put(propKey(type, LOCATION), type == SOURCE ? getSourceDir() : getTargetDirectory());
   }

   @Override
   protected void beforeMigration() {
      FileLookup lookup = FileLookupFactory.newInstance();
      URL url = lookup.lookupFileLocation(Paths.get(getSourceDir(), TEST_CACHE_NAME).toString(), Thread.currentThread().getContextClassLoader());
      for (File f : new File(url.getPath()).listFiles()) {
         String filename = f.getName();
         if (filename.endsWith("_log")) {
            try {
               Files.copy(f.toPath(), new File(f.getParentFile(), filename.replace("_log", ".log")).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
               // should never happen
               throw new RuntimeException(e);
            }
         }

      }
   }
}
