package org.enso.table.data.column.operation.map.text;

import java.util.BitSet;
import org.enso.table.data.column.operation.map.BinaryMapOperation;
import org.enso.table.data.column.operation.map.MapOperationProblemAggregator;
import org.enso.table.data.column.storage.BoolStorage;
import org.enso.table.data.column.storage.SpecializedStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.StringStorage;
import org.enso.table.error.UnexpectedTypeException;
import org.graalvm.polyglot.Context;

public abstract class StringBooleanOp
    extends BinaryMapOperation<String, SpecializedStorage<String>> {
  public StringBooleanOp(String name) {
    super(name);
  }

  protected abstract boolean doString(String a, String b);

  protected boolean doObject(String a, Object o) {
    throw new UnexpectedTypeException("a Text", o.toString());
  }

  @Override
  public BoolStorage runBinaryMap(
      SpecializedStorage<String> storage,
      Object arg,
      MapOperationProblemAggregator problemAggregator) {
    if (arg == null) {
      BitSet newVals = new BitSet();
      BitSet newIsNothing = new BitSet();
      newIsNothing.set(0, storage.size());
      return new BoolStorage(newVals, newIsNothing, storage.size(), false);
    } else if (arg instanceof String argString) {
      BitSet newVals = new BitSet();
      BitSet newIsNothing = new BitSet();
      Context context = Context.getCurrent();
      for (int i = 0; i < storage.size(); i++) {
        if (storage.isNothing(i)) {
          newIsNothing.set(i);
        } else if (doString(storage.getItem(i), argString)) {
          newVals.set(i);
        }

        context.safepoint();
      }
      return new BoolStorage(newVals, newIsNothing, storage.size(), false);
    } else {
      BitSet newVals = new BitSet();
      BitSet newIsNothing = new BitSet();
      Context context = Context.getCurrent();
      for (int i = 0; i < storage.size(); i++) {
        if (storage.isNothing(i)) {
          newIsNothing.set(i);
        } else if (doObject(storage.getItem(i), arg)) {
          newVals.set(i);
        }

        context.safepoint();
      }
      return new BoolStorage(newVals, newIsNothing, storage.size(), false);
    }
  }

  @Override
  public BoolStorage runZip(
      SpecializedStorage<String> storage,
      Storage<?> arg,
      MapOperationProblemAggregator problemAggregator) {
    Context context = Context.getCurrent();
    if (arg instanceof StringStorage v) {
      BitSet newVals = new BitSet();
      BitSet newIsNothing = new BitSet();
      for (int i = 0; i < storage.size(); i++) {
        if (!storage.isNothing(i) && i < v.size() && !v.isNothing(i)) {
          if (doString(storage.getItem(i), v.getItem(i))) {
            newVals.set(i);
          }
        } else {
          newIsNothing.set(i);
        }

        context.safepoint();
      }
      return new BoolStorage(newVals, newIsNothing, storage.size(), false);
    } else {
      BitSet newVals = new BitSet();
      BitSet newIsNothing = new BitSet();
      for (int i = 0; i < storage.size(); i++) {
        if (!storage.isNothing(i) && i < arg.size() && !arg.isNothing(i)) {
          Object x = arg.getItemBoxed(i);
          if (x instanceof String str) {
            if (doString(storage.getItem(i), str)) {
              newVals.set(i);
            }
          } else {
            if (doObject(storage.getItem(i), x)) {
              newVals.set(i);
            }
          }
        } else {
          newIsNothing.set(i);
        }

        context.safepoint();
      }
      return new BoolStorage(newVals, newIsNothing, storage.size(), false);
    }
  }
}
