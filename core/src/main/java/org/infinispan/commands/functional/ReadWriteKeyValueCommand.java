package org.infinispan.commands.functional;

import org.infinispan.commands.CommandInvocationId;
import org.infinispan.commands.Visitor;
import org.infinispan.commands.write.ValueMatcher;
import org.infinispan.commons.api.functional.EntryView.ReadWriteEntryView;
import org.infinispan.commons.marshall.SerializeWith;
import org.infinispan.commons.util.Util;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.functional.impl.EntryViews;

import java.util.Set;
import java.util.function.BiFunction;

public final class ReadWriteKeyValueCommand<K, V, R> extends AbstractWriteKeyCommand<K, V> {

   public static final byte COMMAND_ID = 51;

   private V value;
   private BiFunction<V, ReadWriteEntryView<K, V>, R> f;

   public ReadWriteKeyValueCommand(K key, V value, BiFunction<V, ReadWriteEntryView<K, V>, R> f,
         CommandInvocationId id) {
      super(key, f.getClass().getAnnotation(SerializeWith.class), id);
      this.value = value;
      this.f = f;
   }

   public ReadWriteKeyValueCommand() {
      // No-op, for marshalling
   }

   @Override
   public byte getCommandId() {
      return COMMAND_ID;
   }

   @Override
   public void setParameters(int commandId, Object[] parameters) {
      if (commandId != COMMAND_ID) throw new IllegalStateException("Invalid method id");
      key = parameters[0];
      value = (V) parameters[1];
      f = (BiFunction<V, ReadWriteEntryView<K, V>, R>) parameters[2];
      valueMatcher = (ValueMatcher) parameters[3];
      flags = (Set<Flag>) parameters[4];
      commandInvocationId = (CommandInvocationId) parameters[5];
   }

   @Override
   public Object[] getParameters() {
      return new Object[]{key, value, f, valueMatcher, Flag.copyWithoutRemotableFlags(flags), commandInvocationId};
   }

   @Override
   public boolean isConditional() {
      return true;
   }

   @Override
   public Object perform(InvocationContext ctx) throws Throwable {
      // It's not worth looking up the entry if we're never going to apply the change.
      if (valueMatcher == ValueMatcher.MATCH_NEVER) {
         successful = false;
         return null;
      }

      CacheEntry<K, V> e = ctx.lookupEntry(key);

      // Could be that the key is not local
      if (e == null) return null;

      return f.apply(value, EntryViews.readWrite(e, notifier));
   }

   @Override
   public void updateStatusFromRemoteResponse(Object remoteResponse) {
      // TODO: Customise this generated block
   }

   @Override
   public Object acceptVisitor(InvocationContext ctx, Visitor visitor) throws Throwable {
      return visitor.visitReadWriteKeyValueCommand(ctx, this);
   }

   @Override
   public String toString() {
      return super.toString() + "@" + Util.hexIdHashCode(this);
   }
}
