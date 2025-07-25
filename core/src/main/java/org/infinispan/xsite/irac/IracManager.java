package org.infinispan.xsite.irac;

import java.util.Collection;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import org.infinispan.commands.RequestUUID;
import org.infinispan.commons.util.IntSet;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.metadata.impl.IracMetadata;
import org.infinispan.remoting.transport.Address;
import org.infinispan.topology.CacheTopology;
import org.infinispan.xsite.commands.remote.IracClearKeysRequest;
import org.infinispan.xsite.statetransfer.XSiteState;

/**
 * It manages the keys changed in the local cluster and sends to all asynchronous backup configured.
 * <p>
 * The {@code lockOwner} is the last command (or transaction) who updated the key. It is used to detect conflicting
 * local updates while sending to the remote backups (sites).
 *
 * @author Pedro Ruivo
 * @since 11.0
 */
@Scope(Scopes.NAMED_CACHE)
public interface IracManager {

   /**
    * Sets the {@code key} as changed by the {@code lockOwner}.
    *
    * @param segment The key's segment.
    * @param key     The key changed.
    * @param owner   The lock owner who updated the key.
    */
   void trackUpdatedKey(int segment, Object key, RequestUUID owner);

   /**
    * Similar to {@link #trackUpdatedKey(int, Object, RequestUUID)} but it tracks expired keys instead.
    * <p>
    * Expired key need a different conflict resolution algorithm since remove expired should never win any conflict.
    *
    * @param segment The key's segment.
    * @param key     The key expired.
    * @param owner   The lock owner who updated the key.
    */
   void trackExpiredKey(int segment, Object key, RequestUUID owner);

   /**
    * Tracks a set of keys to be sent to the remote site.
    * <p>
    * There is no much difference between this method and {@link #trackUpdatedKey(int, Object, RequestUUID)}. It just returns
    * a {@link CompletionStage} to notify when the keys were sent. It is required by the cross-site state transfer
    * protocol to know when it has finish.
    *
    * @param stateList The list of {@link XSiteState}.
    * @return A {@link CompletionStage} which is completed when all the keys in {@code stateList} have been sent to the
    * remote site.
    */
   CompletionStage<Void> trackForStateTransfer(Collection<XSiteState> stateList);

   /**
    * Sets all keys as removed.
    *
    * @param sendClear if {@code true}, an {@link IracClearKeysRequest} is sent to the backup sites.
    */
   void trackClear(boolean sendClear);

   /**
    * Removes the state associated to a single key.
    *
    * @param state The state to remove.
    */
   void removeState(IracManagerKeyInfo state);

   /**
    * Notifies a topology changed.
    *
    * @param oldCacheTopology The old {@link CacheTopology}.
    * @param newCacheTopology The new {@link CacheTopology}.
    */
   void onTopologyUpdate(CacheTopology oldCacheTopology, CacheTopology newCacheTopology);

   /**
    * Requests the state stored in this instance for the given {@code segments}.
    *
    * @param requestor The requestor.
    * @param segments  The segments requested.
    */
   void requestState(Address requestor, IntSet segments);

   /**
    * Receives the state related to the {@code key}.
    *
    * @param segment   The key's segment.
    * @param key       The key modified.
    * @param owner     The last {@code lockOwner}.
    * @param tombstone The tombstone (can be {@code null})
    */
   void receiveState(int segment, Object key, RequestUUID owner, IracMetadata tombstone);

   /**
    * Checks if the given key is expired on all other sites. If the key is expired on all other sites this will return
    * true
    *
    * @param key The key to check if it is expired or not
    * @return Whether this key is expired on all other sites
    */
   CompletionStage<Boolean> checkAndTrackExpiration(Object key);

   /**
    * Increase the count of discards.
    */
   void incrementNumberOfDiscards();

   /**
    * Increase the count of conflicts if merge policy discard update (local value wins)
    */
   void incrementNumberOfConflictLocalWins();

   /**
    * Increase the count of conflicts if merge policy applies update (remote value wins)
    */
   void incrementNumberOfConflictRemoteWins();

   /**
    * Increase the count of conflicts if merge policy created a new value (merge remote value with local value)
    */
   void incrementNumberOfConflictMerged();

   /**
    * Checks if the key is present.
    * <p>
    * A key is present as long as its latest update was not confirmed by all remote sites.
    *
    * @param key The key to check.
    * @return {@code true} if the key is present.
    */
   boolean containsKey(Object key);

   /**
    * Returns a {@link Stream} containing all keys that have not been acknowledged by other sites.
    * <p>
    * This stream represents keys for which a corresponding acknowledgment has not been received from all participating
    * sites. This can be used to identify pending operations or potential data inconsistencies.
    *
    * @return A {@link Stream} of unacknowledged keys.
    */
   Stream<IracManagerKeyInfo> pendingKeys();

   /**
    * Checks a remote node's list of {@link IracManagerKeyInfo} for stale or outdated entries.
    * <p>
    * This method iterates through the provided list of key information and identifies entries that are considered
    * stale. For each stale entry found, a cleanup command is invoked to remove the corresponding key on the remote
    * node.
    *
    * @param origin The node that initiated the check.
    * @param keys   The list of key information to be inspected for stale entries.
    */
   void checkStaleKeys(Address origin, Collection<IracManagerKeyInfo> keys);
}
