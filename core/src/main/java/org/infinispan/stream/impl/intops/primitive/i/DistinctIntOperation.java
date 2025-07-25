package org.infinispan.stream.impl.intops.primitive.i;

import java.util.stream.IntStream;

import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoTypeId;
import org.infinispan.stream.impl.intops.IntermediateOperation;

import io.reactivex.rxjava3.core.Flowable;

/**
 * Performs distinct operation on a {@link IntStream}
 */
@ProtoTypeId(ProtoStreamTypeIds.STREAM_INTOP_PRIMITIVE_INT_DISTINCT_OPERATION)
public class DistinctIntOperation implements IntermediateOperation<Integer, IntStream, Integer, IntStream> {
   private static final DistinctIntOperation OPERATION = new DistinctIntOperation();
   private DistinctIntOperation() { }

   @ProtoFactory
   public static DistinctIntOperation getInstance() {
      return OPERATION;
   }

   @Override
   public IntStream perform(IntStream stream) {
      return stream.distinct();
   }

   @Override
   public Flowable<Integer> mapFlowable(Flowable<Integer> input) {
      return input.distinct();
   }
}
