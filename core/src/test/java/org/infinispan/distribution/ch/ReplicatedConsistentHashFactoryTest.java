package org.infinispan.distribution.ch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.infinispan.distribution.ch.impl.OwnershipStatistics;
import org.infinispan.distribution.ch.impl.ReplicatedConsistentHash;
import org.infinispan.distribution.ch.impl.ReplicatedConsistentHashFactory;
import org.infinispan.remoting.transport.Address;
import org.testng.annotations.Test;

/**
 * Test even distribution after membership change
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
@Test(groups = "unit", testName = "distribution.ch.ReplicatedConsistentHashFactoryTest")
public class ReplicatedConsistentHashFactoryTest {

   public void test1() {
      int[] testSegments = { 1, 2, 4, 8, 16, 31, 32, 33, 67, 128};

       ReplicatedConsistentHashFactory factory = ReplicatedConsistentHashFactory.getInstance();
      Address A = Address.random("A");
      Address B = Address.random("B");
      Address C = Address.random("C");
      Address D = Address.random("D");
      List<Address> a = Arrays.asList(A);
      List<Address> ab = Arrays.asList(A, B);
      List<Address> abc = Arrays.asList(A, B, C);
      List<Address> abcd = Arrays.asList(A, B, C, D);
      List<Address> bcd = Arrays.asList(B, C, D);
      List<Address> c = Arrays.asList(C);

      for (int segments : testSegments) {
         ReplicatedConsistentHash ch = factory.create(0, segments, a, null);
         checkDistribution(ch);

         ch = factory.updateMembers(ch, ab, null);
         ch = factory.rebalance(ch);
         checkDistribution(ch);

         ch = factory.updateMembers(ch, abc, null);
         ch = factory.rebalance(ch);
         checkDistribution(ch);

         ch = factory.updateMembers(ch, abcd, null);
         ch = factory.rebalance(ch);
         checkDistribution(ch);

         ch = factory.updateMembers(ch, bcd, null);
         ch = factory.rebalance(ch);
         checkDistribution(ch);

         ch = factory.updateMembers(ch, c, null);
         ch = factory.rebalance(ch);
         checkDistribution(ch);
      }
   }

   private void checkDistribution(ReplicatedConsistentHash ch) {
      int minSegments = Integer.MAX_VALUE, maxSegments = Integer.MIN_VALUE;
      OwnershipStatistics stats = new OwnershipStatistics(ch, ch.getMembers());
      for (Address member : ch.getMembers()) {
         int primary = stats.getPrimaryOwned(member);
         minSegments = Math.min(minSegments, primary);
         maxSegments = Math.max(maxSegments, primary);
         assertEquals(stats.getOwned(member), ch.getNumSegments());
      }
      assertTrue(maxSegments - minSegments <= 1);
   }
}
