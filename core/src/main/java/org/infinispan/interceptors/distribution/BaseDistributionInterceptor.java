package org.infinispan.interceptors.distribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commands.TopologyAffectedCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.functional.ReadOnlyKeyCommand;
import org.infinispan.commands.functional.ReadOnlyManyCommand;
import org.infinispan.commands.read.AbstractDataCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.remote.ClusteredGetAllCommand;
import org.infinispan.commands.remote.ClusteredGetCommand;
import org.infinispan.commands.remote.GetKeysInGroupCommand;
import org.infinispan.commands.write.AbstractDataWriteCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.DataWriteCommand;
import org.infinispan.commands.write.ValueMatcher;
import org.infinispan.commons.CacheException;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.container.entries.NullCacheEntry;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.distribution.DistributionInfo;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.LocalizedCacheTopology;
import org.infinispan.distribution.RemoteValueRetrievedListener;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.KeyPartitioner;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.InvocationSuccessFunction;
import org.infinispan.interceptors.impl.ClusteringInterceptor;
import org.infinispan.remoting.RemoteException;
import org.infinispan.remoting.RpcException;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.ExceptionResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.responses.UnsuccessfulResponse;
import org.infinispan.remoting.rpc.ResponseMode;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.transport.Address;
import org.infinispan.statetransfer.OutdatedTopologyException;
import org.infinispan.topology.CacheTopology;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Base class for distribution of entries across a cluster.
 *
 * @author Manik Surtani
 * @author Mircea.Markus@jboss.com
 * @author Pete Muir
 * @author Dan Berindei <dan@infinispan.org>
 */
public abstract class BaseDistributionInterceptor extends ClusteringInterceptor {
   private static final Log log = LogFactory.getLog(BaseDistributionInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();

   protected DistributionManager dm;
   protected RemoteValueRetrievedListener rvrl;
   protected KeyPartitioner keyPartitioner;

   protected boolean isL1Enabled;
   protected boolean isReplicated;

   private final ReadOnlyManyHelper readOnlyManyHelper = new ReadOnlyManyHelper();
   private final InvocationSuccessFunction primaryReturnHandler = this::primaryReturnHandler;

   @Override
   protected Log getLog() {
      return log;
   }

   @Inject
   public void injectDependencies(DistributionManager distributionManager, RemoteValueRetrievedListener rvrl,
                                  KeyPartitioner keyPartitioner) {
      this.dm = distributionManager;
      this.rvrl = rvrl;
      this.keyPartitioner = keyPartitioner;
   }


   @Start
   public void configure() {
      // Can't rely on the super injectConfiguration() to be called before our injectDependencies() method2
      isL1Enabled = cacheConfiguration.clustering().l1().enabled();
      isReplicated = cacheConfiguration.clustering().cacheMode().isReplicated();
   }

   @Override
   public final Object visitGetKeysInGroupCommand(InvocationContext ctx, GetKeysInGroupCommand command)
         throws Throwable {
      final String groupName = command.getGroupName();
      if (command.isGroupOwner()) {
         //don't go remote if we are an owner.
         return invokeNext(ctx, command);
      }
      Address primaryOwner = dm.getCacheTopology().getDistribution(groupName).primary();
      CompletableFuture<Map<Address, Response>> future = rpcManager.invokeRemotelyAsync(
            Collections.singleton(primaryOwner), command, defaultSyncOptions);
      return asyncInvokeNext(ctx, command, future.thenAccept(responses -> {
         if (!responses.isEmpty()) {
            Response response = responses.values().iterator().next();
            if (response instanceof SuccessfulResponse) {
               //noinspection unchecked
               List<CacheEntry> cacheEntries = (List<CacheEntry>) ((SuccessfulResponse) response).getResponseValue();
               for (CacheEntry entry : cacheEntries) {
                  wrapRemoteEntry(ctx, entry.getKey(), entry, false);
               }
            }
         }
      }));
   }

   @Override
   public final Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      if (ctx.isOriginLocal() && !isLocalModeForced(command)) {
         RpcOptions rpcOptions = rpcManager.getRpcOptionsBuilder(
               isSynchronous(command) ? ResponseMode.SYNCHRONOUS_IGNORE_LEAVERS : ResponseMode.ASYNCHRONOUS).build();
         return asyncInvokeNext(ctx, command, rpcManager.invokeRemotelyAsync(null, command, rpcOptions));
      }
      return invokeNext(ctx, command);
   }

   protected <C extends FlagAffectedCommand & TopologyAffectedCommand> CompletableFuture<Void> remoteGet(
         InvocationContext ctx, C command, Object key, boolean isWrite) {
      LocalizedCacheTopology cacheTopology = checkTopologyId(command);
      int topologyId = cacheTopology.getTopologyId();

      DistributionInfo info = cacheTopology.getDistribution(key);
      if (info.isReadOwner()) {
         if (trace) {
            log.tracef("Key %s became local after wrapping, retrying command. Command topology is %d, current topology is %d",
                  key, command.getTopologyId(), topologyId);
         }
         // The topology has changed between EWI and BDI, let's retry
         if (command.getTopologyId() == topologyId) {
            throw new IllegalStateException();
         }
         throw new OutdatedTopologyException(topologyId);
      }
      if (trace) {
         log.tracef("Perform remote get for key %s. currentTopologyId=%s, owners=%s",
            key, topologyId, info.readOwners());
      }

      ClusteredGetCommand getCommand = cf.buildClusteredGetCommand(key, command.getFlagsBitSet());
      getCommand.setTopologyId(topologyId);
      getCommand.setWrite(isWrite);

      return rpcManager.invokeRemotelyAsync(info.readOwners(), getCommand, staggeredOptions).thenAccept(responses -> {
         for (Response r : responses.values()) {
            if (r instanceof SuccessfulResponse) {
               SuccessfulResponse response = (SuccessfulResponse) r;
               Object responseValue = response.getResponseValue();
               if (responseValue == null) {
                  if (rvrl != null) {
                     rvrl.remoteValueNotFound(key);
                  }
                  wrapRemoteEntry(ctx, key, NullCacheEntry.getInstance(), isWrite);
                  return;
               }
               InternalCacheEntry ice = ((InternalCacheValue) responseValue).toInternalCacheEntry(key);
               if (rvrl != null) {
                  rvrl.remoteValueFound(ice);
               }
               wrapRemoteEntry(ctx, key, ice, isWrite);
               return;
            }
         }
         // If this node has topology higher than some of the nodes and the nodes could not respond
         // with the remote entry, these nodes are blocking the response and therefore we can get only timeouts.
         // Therefore, if we got here it means that we have lower topology than some other nodes and we can wait
         // for it in StateTransferInterceptor and retry the read later.
         // TODO: These situations won't happen as soon as we'll implement 4-phase topology change in ISPN-5021
         throw new OutdatedTopologyException("Did not get any successful response, got " + responses);
      });
   }

   protected void wrapRemoteEntry(InvocationContext ctx, Object key, CacheEntry ice, boolean isWrite) {
      entryFactory.wrapExternalEntry(ctx, key, ice, true, isWrite);
   }

   protected final Object handleNonTxWriteCommand(InvocationContext ctx, AbstractDataWriteCommand command)
         throws Throwable {
      Object key = command.getKey();
      CacheEntry entry = ctx.lookupEntry(key);

      if (isLocalModeForced(command)) {
         if (entry == null) {
            entryFactory.wrapExternalEntry(ctx, key, null, false, true);
         }
         return invokeNext(ctx, command);
      }

      LocalizedCacheTopology cacheTopology = checkTopologyId(command);
      DistributionInfo info = cacheTopology.getDistribution(key);
      if (entry == null) {
         boolean load = shouldLoad(ctx, command, info);
         if (info.isPrimary()) {
            throw new IllegalStateException("Primary owner in writeCH should always be an owner in readCH as well.");
         } else if (ctx.isOriginLocal()) {
            return invokeRemotely(command, info.primary());
         } else {
            if (load) {
               CompletableFuture<?> getFuture = remoteGet(ctx, command, command.getKey(), true);
               return asyncInvokeNext(ctx, command, getFuture);
            } else {
               entryFactory.wrapExternalEntry(ctx, key, null, false, true);
               return invokeNext(ctx, command);
            }
         }
      } else {
         if (info.isPrimary()) {
            return invokeNextThenApply(ctx, command, primaryReturnHandler);
         } else if (ctx.isOriginLocal()) {
            return invokeRemotely(command, info.primary());
         } else {
            return invokeNext(ctx, command);
         }
      }
   }

   private boolean shouldLoad(InvocationContext ctx, AbstractDataWriteCommand command, DistributionInfo info) {
      if (!command.hasAnyFlag(FlagBitSets.SKIP_REMOTE_LOOKUP)) {
         VisitableCommand.LoadType loadType = command.loadType();
         switch (loadType) {
            case DONT_LOAD:
               return false;
            case OWNER:
               return info.isPrimary() || (info.isWriteOwner() && !ctx.isOriginLocal());
            case PRIMARY:
               return info.isPrimary();
            default:
               throw new IllegalStateException();
         }
      } else {
         return false;
      }
   }

   private Object invokeRemotely(DataWriteCommand command, Address primaryOwner) {
      if (trace) log.tracef("I'm not the primary owner, so sending the command to the primary owner(%s) in order to be forwarded", primaryOwner);
      boolean isSyncForwarding = isSynchronous(command) || command.isReturnValueExpected();

      CompletableFuture<Map<Address, Response>> remoteInvocation;
      try {
         remoteInvocation = rpcManager.invokeRemotelyAsync(Collections.singletonList(primaryOwner), command,
               isSyncForwarding ? defaultSyncOptions : defaultAsyncOptions);
      } catch (Throwable t) {
         command.setValueMatcher(command.getValueMatcher().matcherForRetry());
         throw t;
      }
      if (isSyncForwarding) {
         return asyncValue(remoteInvocation.handle((responses, t) -> {
            command.setValueMatcher(command.getValueMatcher().matcherForRetry());
            CompletableFutures.rethrowException(t);

            Object primaryResult = getResponseFromPrimaryOwner(primaryOwner, responses);
            command.updateStatusFromRemoteResponse(primaryResult);
            return primaryResult;
         }));
      } else {
         return null;
      }
   }

   private Object primaryReturnHandler(InvocationContext ctx, VisitableCommand visitableCommand, Object localResult) {
      DataWriteCommand command = (DataWriteCommand) visitableCommand;
      if (!command.isSuccessful()) {
         if (trace) log.tracef("Skipping the replication of the conditional command as it did not succeed on primary owner (%s).", command);
         return localResult;
      }
      LocalizedCacheTopology cacheTopology = checkTopologyId(command);
      DistributionInfo distributionInfo = cacheTopology.getDistribution(command.getKey());
      Collection<Address> owners = distributionInfo.writeOwners();
      if (owners.size() == 1) {
         // There are no backups, skip the replication part.
         return localResult;
      }
      Collection<Address> recipients = isReplicated ? null : owners;

      // Cache the matcher and reset it if we get OOTE (or any other exception) from backup
      ValueMatcher originalMatcher = command.getValueMatcher();
      // Ignore the previous value on the backup owners
      command.setValueMatcher(ValueMatcher.MATCH_ALWAYS);
      RpcOptions rpcOptions = determineRpcOptionsForBackupReplication(rpcManager, isSynchronous(command), recipients);
      CompletableFuture<Map<Address, Response>> remoteInvocation =
            rpcManager.invokeRemotelyAsync(recipients, command, rpcOptions);
      return asyncValue(remoteInvocation.handle((responses, t) -> {
         // Switch to the retry policy, in case the primary owner changed and the write already succeeded on the new primary
         command.setValueMatcher(originalMatcher.matcherForRetry());
         CompletableFutures.rethrowException(t instanceof RemoteException ? t.getCause() : t);
         return localResult;
      }));
   }

   private RpcOptions determineRpcOptionsForBackupReplication(RpcManager rpc, boolean isSync, Collection<Address> recipients) {
      if (isSync) {
         // If no recipients, means a broadcast, so we can ignore leavers
         return recipients == null ?
               rpc.getRpcOptionsBuilder(ResponseMode.SYNCHRONOUS_IGNORE_LEAVERS).build() :
               defaultSyncOptions;
      } else {
         return defaultAsyncOptions;
      }
   }

   private Object getResponseFromPrimaryOwner(Address primaryOwner, Map<Address, Response> addressResponseMap) {
      Response fromPrimaryOwner = addressResponseMap.get(primaryOwner);
      if (fromPrimaryOwner == null) {
         if (trace) log.tracef("Primary owner %s returned null", primaryOwner);
         return null;
      }
      if (fromPrimaryOwner.isSuccessful()) {
         return ((SuccessfulResponse) fromPrimaryOwner).getResponseValue();
      }

      if (addressResponseMap.get(primaryOwner) instanceof CacheNotFoundResponse) {
         // This means the cache wasn't running on the primary owner, so the command wasn't executed.
         // We throw an OutdatedTopologyException, StateTransferInterceptor will catch the exception and
         // it will then retry the command.
         throw new OutdatedTopologyException("Cache is no longer running on primary owner " + primaryOwner);
      }

      Throwable cause = fromPrimaryOwner instanceof ExceptionResponse ? ((ExceptionResponse)fromPrimaryOwner).getException() : null;
      throw new CacheException("Got unsuccessful response from primary owner: " + fromPrimaryOwner, cause);
   }

   @Override
   public Object visitGetAllCommand(InvocationContext ctx, GetAllCommand command) throws Throwable {
      if (command.hasAnyFlag(FlagBitSets.CACHE_MODE_LOCAL | FlagBitSets.SKIP_REMOTE_LOOKUP)) {
         return invokeNext(ctx, command);
      }

      if (!ctx.isOriginLocal()) {
         for (Object key : command.getKeys()) {
            if (ctx.lookupEntry(key) == null) {
               return handleMissingEntryOnRead(command);
            }
         }
         return invokeNext(ctx, command);
      }

      LocalizedCacheTopology cacheTopology = checkTopologyId(command);
      Map<Address, List<Object>> requestedKeys = getKeysByOwner(ctx, command.getKeys(), cacheTopology, null);
      if (requestedKeys.isEmpty()) {
         return invokeNext(ctx, command);
      }

      CompletableFuture<Void> allFuture = remoteGetAll(ctx, command, requestedKeys);
      return asyncInvokeNext(ctx, command, allFuture);
   }

   protected CompletableFuture<Void> remoteGetAll(InvocationContext ctx, GetAllCommand command, Map<Address, List<Object>> requestedKeys) {
      GlobalTransaction gtx = ctx.isInTxScope() ? ((TxInvocationContext) ctx).getGlobalTransaction() : null;
      CompletableFutureWithCounter allFuture = new CompletableFutureWithCounter(requestedKeys.size());

      for (Map.Entry<Address, List<Object>> pair : requestedKeys.entrySet()) {
         List<Object> keys = pair.getValue();
         ClusteredGetAllCommand clusteredGetAllCommand = cf.buildClusteredGetAllCommand(keys, command.getFlagsBitSet(), gtx);
         rpcManager.invokeRemotelyAsync(Collections.singleton(pair.getKey()), clusteredGetAllCommand, defaultSyncOptions).whenComplete((responseMap, throwable) -> {
            if (throwable != null) {
               allFuture.completeExceptionally(throwable);
            }
            Response response = getSingleSuccessfulResponseOrFail(responseMap, allFuture);
            if (response == null) return;
            Object responseValue = ((SuccessfulResponse) response).getResponseValue();
            if (responseValue instanceof InternalCacheValue[]) {
               InternalCacheValue[] values = (InternalCacheValue[]) responseValue;
               int counterValue;
               synchronized (allFuture) {
                  for (int i = 0; i < keys.size(); ++i) {
                     Object key = keys.get(i);
                     InternalCacheValue value = values[i];
                     CacheEntry entry = value == null ? NullCacheEntry.getInstance() : value.toInternalCacheEntry(key);
                     wrapRemoteEntry(ctx, key, entry, false);
                  }
                  counterValue = --allFuture.counter;
               }
               // complete the future after sync block!
               if (counterValue == 0) {
                  allFuture.complete(null);
               }
            } else {
               allFuture.completeExceptionally(new IllegalStateException("Unexpected response value: " + responseValue));
            }
         });
      }
      return allFuture;
   }

   @Override
   public Object visitReadOnlyManyCommand(InvocationContext ctx, ReadOnlyManyCommand command) throws Throwable {
      return handleFunctionalReadManyCommand(ctx, command, readOnlyManyHelper);
   }

   protected <C extends TopologyAffectedCommand & FlagAffectedCommand> Object handleFunctionalReadManyCommand(
         InvocationContext ctx, C command, ReadManyCommandHelper<C> helper) {
      // We cannot merge this method with visitGetAllCommand because this can't wrap entries into context
      // TODO: repeatable-reads are not implemented - see visitReadOnlyKeyCommand
      if (command.hasAnyFlag(FlagBitSets.CACHE_MODE_LOCAL | FlagBitSets.SKIP_REMOTE_LOOKUP)) {
         return handleLocalOnlyReadManyCommand(ctx, command, helper.keys(command));
      }

      LocalizedCacheTopology cacheTopology = checkTopologyId(command);
      Collection<?> keys = helper.keys(command);
      if (!ctx.isOriginLocal()) {
         return handleRemoteReadManyCommand(ctx, command, keys, helper);
      }
      if (keys.isEmpty()) {
         return Stream.empty();
      }

      ConsistentHash ch = cacheTopology.getReadConsistentHash();
      int estimateForOneNode = 2 * keys.size() / ch.getMembers().size();
      List<Object> availableKeys = new ArrayList<>(estimateForOneNode);
      Map<Address, List<Object>> requestedKeys = getKeysByOwner(ctx, keys, cacheTopology, availableKeys);

      // TODO: while this works in a non-blocking way, the returned stream is not lazy as the functional
      // contract suggests. Traversable is also not honored as it is executed only locally on originator.
      // On FutureMode.ASYNC, there should be one command per target node going from the top level
      // to allow retries in StateTransferInterceptor in case of topology change.
      MergingCompletableFuture<Object> allFuture = new MergingCompletableFuture<>(
         ctx, requestedKeys.size() + (availableKeys.isEmpty() ? 0 : 1),
         new Object[keys.size()], helper::transformResult);

      handleLocallyAvailableKeys(ctx, command, availableKeys, allFuture, helper);
      int pos = availableKeys.size();
      for (Map.Entry<Address, List<Object>> addressKeys : requestedKeys.entrySet()) {
         List<Object> keysForAddress = addressKeys.getValue();
         remoteReadMany(addressKeys.getKey(), keysForAddress, ctx, command, allFuture, pos, helper);
         pos += keysForAddress.size();
      }
      return asyncValue(allFuture);
   }

   private Object handleLocalOnlyReadManyCommand(InvocationContext ctx, VisitableCommand command, Collection<?> keys) {
      for (Object key : keys) {
         if (ctx.lookupEntry(key) == null) {
            entryFactory.wrapExternalEntry(ctx, key, NullCacheEntry.getInstance(), true, false);
         }
      }
      return invokeNext(ctx, command);
   }

   private <C extends TopologyAffectedCommand & VisitableCommand> Object handleRemoteReadManyCommand(
         InvocationContext ctx, C command, Collection<?> keys, InvocationSuccessFunction remoteReturnHandler) {
      for (Object key : keys) {
         if (ctx.lookupEntry(key) == null) {
            return handleMissingEntryOnRead(command);
         }
      }
      return invokeNextThenApply(ctx, command, remoteReturnHandler);
   }

   private <C extends ReplicableCommand> void remoteReadMany(Address owner, List<Object> keys,
                                                             InvocationContext ctx, C command,
                                                             MergingCompletableFuture<Object> allFuture,
                                                             int destinationIndex,
                                                             ReadManyCommandHelper<C> helper) {
      ReplicableCommand remoteCommand = helper.copyForRemote(command, keys, ctx);
      rpcManager.invokeRemotelyAsync(Collections.singleton(owner), remoteCommand, defaultSyncOptions)
         .whenComplete((responseMap, throwable) -> {
            if (throwable != null) {
               allFuture.completeExceptionally(throwable);
            }
            Response response = getSingleSuccessfulResponseOrFail(responseMap, allFuture);
            if (response == null) return;
            try {
               Object responseValue = ((SuccessfulResponse) response).getResponseValue();
               Object[] values = unwrapFunctionalManyResultOnOrigin(ctx, keys, responseValue);
               if (values != null) {
                  System.arraycopy(values, 0, allFuture.results, destinationIndex, values.length);
                  allFuture.countDown();
               } else {
                  allFuture.completeExceptionally(new IllegalStateException("Unexpected response value " + responseValue));
               }
            } catch (Throwable t) {
               allFuture.completeExceptionally(t);
            }
         });
   }

   private <C extends VisitableCommand> void handleLocallyAvailableKeys(
         InvocationContext ctx, C command, List<Object> availableKeys,
         MergingCompletableFuture<Object> allFuture, ReadManyCommandHelper<C> helper) {
      if (availableKeys.isEmpty()) {
         return;
      }
      C localCommand = helper.copyForLocal(command, availableKeys);
      invokeNextAndHandle(ctx, localCommand, (rCtx, rCommand, rv, throwable) -> {
         if (throwable != null) {
            allFuture.completeExceptionally(throwable);
         } else {
            try {
               helper.applyLocalResult(allFuture, rv);
               allFuture.countDown();
            } catch (Throwable t) {
               allFuture.completeExceptionally(t);
            }
         }
         return asyncValue(allFuture);
      });
   }

   private Map<Address, List<Object>> getKeysByOwner(InvocationContext ctx, Collection<?> keys,
                                                     LocalizedCacheTopology cacheTopology,
                                                     List<Object> availableKeys) {
      int capacity = cacheTopology.getMembers().size();
      Map<Address, List<Object>> requestedKeys = new HashMap<>(capacity);
      int estimateForOneNode = 2 * keys.size() / capacity;
      for (Object key : keys) {
         CacheEntry entry = ctx.lookupEntry(key);
         if (entry == null) {
            DistributionInfo distributionInfo = cacheTopology.getDistribution(key);
            // Let's try to minimize the number of messages by preferring owner to which we've already
            // decided to send message
            boolean foundExisting = false;
            for (Address address : distributionInfo.readOwners()) {
               if (address.equals(rpcManager.getAddress())) {
                  throw new IllegalStateException("Entry should be always wrapped!");
               }
               List<Object> list = requestedKeys.get(address);
               if (list != null) {
                  list.add(key);
                  foundExisting = true;
                  break;
               }
            }
            if (!foundExisting) {
               List<Object> list = new ArrayList<>(estimateForOneNode);
               list.add(key);
               requestedKeys.put(distributionInfo.primary(), list);
            }
         } else if (availableKeys != null) {
            availableKeys.add(key);
         }
      }
      return requestedKeys;
   }

   protected Object wrapFunctionalManyResultOnNonOrigin(InvocationContext rCtx, Collection<?> keys, Object[] values) {
      return values;
   }

   protected Object[] unwrapFunctionalManyResultOnOrigin(InvocationContext ctx, List<Object> keys, Object responseValue) {
      return responseValue instanceof Object[] ? (Object[]) responseValue : null;
   }

   protected Response getSingleSuccessfulResponseOrFail(Map<Address, Response> responseMap, CompletableFuture<?> future) {
      Iterator<Response> it = responseMap.values().iterator();
      if (!it.hasNext()) {
         future.completeExceptionally(new RpcException("Expected one response"));
         return null;
      } else {
         Response response = it.next();
         if (it.hasNext()) {
            future.completeExceptionally(new IllegalStateException("Too many responses " + responseMap));
            return null;
         }
         if (!response.isSuccessful()) {
            // CHECKME: The command is sent with current topology and deferred until the node gets our topology;
            // therefore if it returns unsuccessful response we can assume that there is a newer topology
            future.completeExceptionally(new OutdatedTopologyException("Remote node has higher topology, response " + response));
            return null;
         }
         return response;
      }
   }

   protected static class ArrayIterator {
      private final Object[] array;
      private int pos = 0;

      public ArrayIterator(Object[] array) {
         this.array = array;
      }

      public void add(Object item) {
         array[pos] = item;
         ++pos;
      }

      public void combine(ArrayIterator other) {
         throw new UnsupportedOperationException("The stream is not supposed to be parallel");
      }
   }

   // This class sis very similar to CountDownCompletableFuture but it expect external synchronization,
   // That comes handy when we have to sync anyway on different item, for example the context
   private static class CompletableFutureWithCounter extends CompletableFuture<Void> {
      private int counter;

      public CompletableFutureWithCounter(int counter) {
         this.counter = counter;
      }
   }

   protected static class CountDownCompletableFuture extends CompletableFuture<Object> {
      protected final InvocationContext ctx;
      protected final AtomicInteger counter;

      public CountDownCompletableFuture(InvocationContext ctx, int participants) {
         if (trace) log.tracef("Creating shortcut countdown with %d participants", participants);
         this.ctx = ctx;
         this.counter = new AtomicInteger(participants);
      }

      public void countDown() {
         if (counter.decrementAndGet() == 0) {
            complete(result());
         }
      }

      public void increment() {
         int preValue = counter.getAndIncrement();
         if (preValue == 0) {
            throw new IllegalStateException();
         }
      }

      protected Object result() {
         return null;
      }
   }

   protected static class MergingCompletableFuture<T> extends CountDownCompletableFuture {
      private final Function<T[], Object> transform;
      protected final T[] results;

      public MergingCompletableFuture(InvocationContext ctx, int participants, T[] results, Function<T[], Object> transform) {
         super(ctx, participants);
         // results can be null if the command has flag IGNORE_RETURN_VALUE
         this.results = results;
         this.transform = transform;
      }

      @Override
      protected Object result() {
         return transform == null || results == null ? null : transform.apply(results);
      }
   }

   private Object visitGetCommand(InvocationContext ctx, AbstractDataCommand command) throws Throwable {
      return ctx.lookupEntry(command.getKey()) == null ? onEntryMiss(ctx, command) : invokeNext(ctx, command);
   }

   private Object onEntryMiss(InvocationContext ctx, AbstractDataCommand command) {
      return ctx.isOriginLocal() ?
            handleMissingEntryOnLocalRead(ctx, command) :
            handleMissingEntryOnRead(command);
   }

   private Object handleMissingEntryOnLocalRead(InvocationContext ctx, AbstractDataCommand command) {
      return readNeedsRemoteValue(command) ?
            asyncInvokeNext(ctx, command, remoteGet(ctx, command, command.getKey(), false)) :
            null;
   }

   protected final Object handleMissingEntryOnRead(TopologyAffectedCommand command) {
      // If we have the entry in context it means that we are read owners, so we don't have to check the topology
      CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
      int currentTopologyId = cacheTopology.getTopologyId();
      int cmdTopology = command.getTopologyId();
      if (cmdTopology < currentTopologyId) {
         return UnsuccessfulResponse.INSTANCE;
      } else {
         // If cmdTopology > currentTopologyId: the topology of this node is outdated
         // TODO: This situation won't happen as soon as we'll implement 4-phase topology change in ISPN-5021
         // (then, Tx.readCH and T(x+1).readCH will always have common subset of nodes so we'll be safe here
         // to return UnsuccessfulResponse
         // If cmdTopology == currentTopologyId: between STI and BDI this node had different topology, therefore
         // the entry was not loaded into context. Retry again locally.
         throw new OutdatedTopologyException(cmdTopology);
      }
   }

   @Override
   public Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command)
         throws Throwable {
      return visitGetCommand(ctx, command);
   }

   @Override
   public Object visitGetCacheEntryCommand(InvocationContext ctx,
                                           GetCacheEntryCommand command) throws Throwable {
      return visitGetCommand(ctx, command);
   }

   @Override
   public Object visitReadOnlyKeyCommand(InvocationContext ctx, ReadOnlyKeyCommand command)
         throws Throwable {
      // TODO: repeatable-reads are not implemented, these need to keep the read values on remote side for the duration
      // of the transaction, and that requires synchronous invocation of the readonly command on all owners.
      // For better consistency, use versioning and write skew check that will fail the transaction when we apply
      // the function on different version of the entry than the one previously read
      Object key = command.getKey();
      CacheEntry entry = ctx.lookupEntry(key);
      if (entry != null) {
         if (ctx.isOriginLocal()) {
            // the entry is owned locally (it is NullCacheEntry if it was not found), no need to go remote
            return invokeNext(ctx, command);
         } else {
            return invokeNextThenApply(ctx, command, (rCtx, rCommand, rv) ->
                  wrapFunctionalResultOnNonOriginOnReturn(rv, entry));
         }
      }
      if (!ctx.isOriginLocal()) {
         return handleMissingEntryOnRead(command);
      }
      if (readNeedsRemoteValue(command)) {
         LocalizedCacheTopology cacheTopology = checkTopologyId(command);
         Collection<Address> owners = cacheTopology.getDistribution(key).readOwners();
         if (trace)
            log.tracef("Doing a remote get for key %s in topology %d to %s", key, cacheTopology.getTopologyId(), owners);

         ReadOnlyKeyCommand remoteCommand = remoteReadOnlyCommand(ctx, command);
         // make sure that the command topology is set to the value according which we route it
         remoteCommand.setTopologyId(cacheTopology.getTopologyId());

         CompletableFuture<Map<Address, Response>> rpc = rpcManager.invokeRemotelyAsync(owners, remoteCommand, staggeredOptions);
         return asyncValue(rpc.thenApply(responses -> {
               for (Response rsp : responses.values()) {
                  if (rsp.isSuccessful()) {
                     return unwrapFunctionalResultOnOrigin(ctx, key, ((SuccessfulResponse) rsp).getResponseValue());
                  }
               }
               // On receiver side the command topology id is checked and if it's too new, the command is delayed.
               // We can assume that we miss successful response only because the owners already have new topology
               // in which they're not owners - we'll wait for this topology, then.
               throw new OutdatedTopologyException("We haven't found an owner");
            }));
      } else {
         // This has LOCAL flags, just wrap NullCacheEntry and let the command run
         entryFactory.wrapExternalEntry(ctx, key, NullCacheEntry.getInstance(), true, false);
         return invokeNext(ctx, command);
      }
   }

   protected ReadOnlyKeyCommand remoteReadOnlyCommand(InvocationContext ctx, ReadOnlyKeyCommand command) {
      return command;
   }

   protected Object wrapFunctionalResultOnNonOriginOnReturn(Object rv, CacheEntry entry) {
      return rv;
   }

   protected Object unwrapFunctionalResultOnOrigin(InvocationContext ctx, Object key, Object responseValue) {
      return responseValue;
   }

   protected LocalizedCacheTopology checkTopologyId(TopologyAffectedCommand command) {
      LocalizedCacheTopology cacheTopology = dm.getCacheTopology();
      int currentTopologyId = cacheTopology.getTopologyId();
      int cmdTopology = command.getTopologyId();
      if (currentTopologyId != cmdTopology && cmdTopology != -1) {
         throw new OutdatedTopologyException("Cache topology changed while the command was executing: expected " +
            cmdTopology + ", got " + currentTopologyId);
      }
      if (trace) {
         log.tracef("Current topology %d, command topology %d", currentTopologyId, cmdTopology);
      }
      return cacheTopology;
   }

   /**
    * @return {@code true} if the value is not available on the local node and a read command is allowed to
    * fetch it from a remote node. Does not check if the value is already in the context.
    */
   protected boolean readNeedsRemoteValue(AbstractDataCommand command) {
      return !command.hasAnyFlag(FlagBitSets.CACHE_MODE_LOCAL | FlagBitSets.SKIP_REMOTE_LOOKUP);
   }

   @FunctionalInterface
   protected interface RemoteReadManyCommandBuilder<C> {
      ReplicableCommand build(InvocationContext ctx, C command, List<Object> keys);
   }

   protected interface ReadManyCommandHelper<C> extends InvocationSuccessFunction {
      Collection<?> keys(C command);
      C copyForLocal(C command, List<Object> keys);
      ReplicableCommand copyForRemote(C command, List<Object> keys, InvocationContext ctx);
      void applyLocalResult(MergingCompletableFuture allFuture, Object rv);
      Object transformResult(Object[] results);
   }

   protected class ReadOnlyManyHelper implements ReadManyCommandHelper<ReadOnlyManyCommand> {
      @Override
      public Object apply(InvocationContext rCtx, VisitableCommand rCommand, Object rv) throws Throwable {
         return wrapFunctionalManyResultOnNonOrigin(rCtx, ((ReadOnlyManyCommand) rCommand).getKeys(), ((Stream) rv).toArray());
      }

      @Override
      public Collection<?> keys(ReadOnlyManyCommand command) {
         return command.getKeys();
      }

      @Override
      public ReadOnlyManyCommand copyForLocal(ReadOnlyManyCommand command, List<Object> keys) {
         return new ReadOnlyManyCommand(command).withKeys(keys);
      }

      @Override
      public ReplicableCommand copyForRemote(ReadOnlyManyCommand command, List<Object> keys, InvocationContext ctx) {
         return new ReadOnlyManyCommand(command).withKeys(keys);
      }

      @Override
      public void applyLocalResult(MergingCompletableFuture allFuture, Object rv) {
         Supplier<ArrayIterator> supplier = () -> new ArrayIterator(allFuture.results);
         BiConsumer<ArrayIterator, Object> consumer = ArrayIterator::add;
         BiConsumer<ArrayIterator, ArrayIterator> combiner = ArrayIterator::combine;
         ((Stream) rv).collect(supplier, consumer, combiner);
      }

      @Override
      public Object transformResult(Object[] results) {
         return Arrays.stream(results);
      }
   }

}
