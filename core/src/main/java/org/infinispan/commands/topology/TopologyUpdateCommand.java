package org.infinispan.commands.topology;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.marshall.protostream.impl.WrappedMessages;
import org.infinispan.partitionhandling.AvailabilityMode;
import org.infinispan.protostream.WrappedMessage;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoTypeId;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.NodeVersion;
import org.infinispan.topology.CacheTopology;

/**
 * Coordinator to member:
 * The coordinator is updating the consistent hash.
 * Used to signal the end of rebalancing as well.
 *
 * @author Ryan Emerson
 * @since 11.0
 */
@ProtoTypeId(ProtoStreamTypeIds.TOPOLOGY_UPDATE_COMMAND)
public class TopologyUpdateCommand extends AbstractCacheControlCommand {

   @ProtoField(1)
   final String cacheName;

   @ProtoField(2)
   final WrappedMessage currentCH;

   @ProtoField(3)
   final WrappedMessage pendingCH;

   @ProtoField(4)
   final CacheTopology.Phase phase;

   @ProtoField(5)
   final List<Address> actualMembers;

   @ProtoField(6)
   final List<UUID> persistentUUIDs;

   @ProtoField(7)
   final AvailabilityMode availabilityMode;

   @ProtoField(8)
   final int rebalanceId;

   @ProtoField(9)
   final int topologyId;

   @ProtoField(10)
   final int viewId;

   @ProtoFactory
   TopologyUpdateCommand(String cacheName, WrappedMessage currentCH, WrappedMessage pendingCH,
                         CacheTopology.Phase phase, List<Address> actualMembers,
                         List<UUID> persistentUUIDs, AvailabilityMode availabilityMode,
                         int rebalanceId, int topologyId, int viewId) {
      this.cacheName = cacheName;
      this.currentCH = currentCH;
      this.pendingCH = pendingCH;
      this.phase = phase;
      this.actualMembers = actualMembers;
      this.persistentUUIDs = persistentUUIDs;
      this.availabilityMode = availabilityMode;
      this.rebalanceId = rebalanceId;
      this.topologyId = topologyId;
      this.viewId = viewId;
   }

   public TopologyUpdateCommand(String cacheName, Address origin, CacheTopology cacheTopology,
                                AvailabilityMode availabilityMode, int viewId) {
      super(origin);
      this.cacheName = cacheName;
      this.topologyId = cacheTopology.getTopologyId();
      this.rebalanceId = cacheTopology.getRebalanceId();
      this.currentCH = new WrappedMessage(cacheTopology.getCurrentCH());
      this.pendingCH = new WrappedMessage(cacheTopology.getPendingCH());
      this.phase = cacheTopology.getPhase();
      this.availabilityMode = availabilityMode;
      this.actualMembers = cacheTopology.getActualMembers();
      this.persistentUUIDs = cacheTopology.getMembersPersistentUUIDs();
      this.viewId = viewId;
   }

   @Override
   public CompletionStage<?> invokeAsync(GlobalComponentRegistry gcr) throws Throwable {
      CacheTopology topology = new CacheTopology(topologyId, rebalanceId, getCurrentCH(), getPendingCH(), phase,
            actualMembers, persistentUUIDs);
      return gcr.getLocalTopologyManager()
            .handleTopologyUpdate(cacheName, topology, availabilityMode, viewId, origin);
   }

   public String getCacheName() {
      return cacheName;
   }

   public ConsistentHash getCurrentCH() {
      return WrappedMessages.unwrap(currentCH);
   }

   public ConsistentHash getPendingCH() {
      return WrappedMessages.unwrap(pendingCH);
   }

   public CacheTopology.Phase getPhase() {
      return phase;
   }

   public AvailabilityMode getAvailabilityMode() {
      return availabilityMode;
   }

   public int getTopologyId() {
      return topologyId;
   }

   @Override
   public NodeVersion supportedSince() {
      return NodeVersion.SIXTEEN;
   }

   @Override
   public String toString() {
      return "TopologyUpdateCommand{" +
            "cacheName='" + cacheName + '\'' +
            ", origin=" + origin +
            ", currentCH=" + getCurrentCH() +
            ", pendingCH=" + getPendingCH() +
            ", phase=" + phase +
            ", actualMembers=" + actualMembers +
            ", persistentUUIDs=" + persistentUUIDs +
            ", availabilityMode=" + availabilityMode +
            ", rebalanceId=" + rebalanceId +
            ", topologyId=" + topologyId +
            ", viewId=" + viewId +
            '}';
   }
}
