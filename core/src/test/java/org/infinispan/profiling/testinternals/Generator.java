package org.infinispan.profiling.testinternals;

import java.util.List;
import java.util.Random;

import org.infinispan.remoting.transport.Address;

public class Generator {

   private static final Random r = new Random();

   public static String getRandomString() {
      return getRandomString(10);
   }

   public static String getRandomString(int maxKeySize) {
      StringBuilder sb = new StringBuilder();
      int len = r.nextInt(maxKeySize) + 1;
      for (int i = 0; i < len; i++) {
         sb.append((char) ('A' + r.nextInt(26)));
      }
      return sb.toString();
   }

   public static <T> T getRandomElement(List<T> list) {
      return list.get(r.nextInt(list.size()));
   }

   public static Object createRandomKey() {
      return Integer.toHexString(r.nextInt(Integer.MAX_VALUE));
   }

   public static byte[] getRandomByteArray(int maxByteArraySize) {
      int sz = r.nextInt(maxByteArraySize);
      byte[] b = new byte[sz];
      for (int i=0; i<sz; i++) b[i] = (byte) r.nextInt(Byte.MAX_VALUE);
      return b;
   }

   public static Address generateAddress() {
      return Address.random();
   }
}
