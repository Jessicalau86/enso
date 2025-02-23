package org.enso.table.data.column.builder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.BitSet;
import java.util.Objects;
import org.enso.base.polyglot.NumericConverter;
import org.enso.table.data.column.operation.cast.ToFloatStorageConverter;
import org.enso.table.data.column.storage.BoolStorage;
import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.numeric.AbstractLongStorage;
import org.enso.table.data.column.storage.numeric.BigIntegerStorage;
import org.enso.table.data.column.storage.numeric.DoubleStorage;
import org.enso.table.data.column.storage.type.BigDecimalType;
import org.enso.table.data.column.storage.type.BigIntegerType;
import org.enso.table.data.column.storage.type.BooleanType;
import org.enso.table.data.column.storage.type.FloatType;
import org.enso.table.data.column.storage.type.IntegerType;
import org.enso.table.data.column.storage.type.StorageType;
import org.enso.table.error.ValueTypeMismatchException;
import org.enso.table.problems.ProblemAggregator;
import org.enso.table.util.BitSets;

/** A builder for floating point columns. */
public class DoubleBuilder extends NumericBuilder {
  DoubleBuilder(
      BitSet isNothing, long[] data, int currentSize, ProblemAggregator problemAggregator) {
    super(isNothing, data, currentSize);
    precisionLossAggregator = new PrecisionLossAggregator(problemAggregator);
  }

  @Override
  public void retypeToMixed(Object[] items) {
    throw new IllegalStateException(
        "The DoubleBuilder cannot be retyped to the Mixed type, because it would lose type"
            + " information about integers that were converted to doubles. If recasting is needed,"
            + " InferringDoubleBuilder should be used instead. This error leaking is a bug in the"
            + " Table library.");
  }

  @Override
  public boolean canRetypeTo(StorageType type) {
    return type instanceof BigDecimalType;
  }

  @Override
  public TypedBuilder retypeTo(StorageType type) {
    if (type instanceof BigDecimalType) {
      BigDecimalBuilder res = new BigDecimalBuilder(currentSize);
      for (int i = 0; i < currentSize; i++) {
        if (isNothing.get(i)) {
          res.appendNulls(1);
        } else {
          double d = Double.longBitsToDouble(data[i]);
          BigDecimal bigDecimal = BigDecimal.valueOf(d);
          res.appendNoGrow(bigDecimal);
        }
      }
      return res;
    } else {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public StorageType getType() {
    return FloatType.FLOAT_64;
  }

  @Override
  public void appendNoGrow(Object o) {
    if (o == null) {
      isNothing.set(currentSize++);
    } else if (NumericConverter.isFloatLike(o)) {
      double value = NumericConverter.coerceToDouble(o);
      data[currentSize++] = Double.doubleToRawLongBits(value);
    } else if (NumericConverter.isCoercibleToLong(o)) {
      long value = NumericConverter.coerceToLong(o);
      double converted = convertIntegerToDouble(value);
      data[currentSize++] = Double.doubleToRawLongBits(converted);
    } else if (o instanceof BigInteger bigInteger) {
      double converted = convertBigIntegerToDouble(bigInteger);
      data[currentSize++] = Double.doubleToRawLongBits(converted);
    } else {
      throw new ValueTypeMismatchException(getType(), o);
    }
  }

  @Override
  public boolean accepts(Object o) {
    return NumericConverter.isCoercibleToDouble(o);
  }

  @Override
  public void appendBulkStorage(Storage<?> storage) {
    if (Objects.equals(storage.getType(), FloatType.FLOAT_64)) {
      if (storage instanceof DoubleStorage doubleStorage) {
        int n = doubleStorage.size();
        ensureFreeSpaceFor(n);
        System.arraycopy(doubleStorage.getRawData(), 0, data, currentSize, n);
        BitSets.copy(doubleStorage.getIsNothingMap(), isNothing, currentSize, n);
        currentSize += n;
      } else {
        throw new IllegalStateException(
            "Unexpected storage implementation for type DOUBLE: "
                + storage
                + ". This is a bug in the Table library.");
      }
    } else if (storage.getType() instanceof IntegerType) {
      if (storage instanceof AbstractLongStorage longStorage) {
        int n = longStorage.size();
        BitSets.copy(longStorage.getIsNothingMap(), isNothing, currentSize, n);
        for (int i = 0; i < n; i++) {
          long item = longStorage.getItem(i);
          double converted = convertIntegerToDouble(item);
          data[currentSize++] = Double.doubleToRawLongBits(converted);
        }
      } else {
        throw new IllegalStateException(
            "Unexpected storage implementation for type INTEGER: "
                + storage
                + ". This is a bug in the Table library.");
      }
    } else if (storage.getType() instanceof BigIntegerType) {
      if (storage instanceof BigIntegerStorage bigIntegerStorage) {
        int n = bigIntegerStorage.size();
        for (int i = 0; i < n; i++) {
          BigInteger item = bigIntegerStorage.getItem(i);
          if (item == null) {
            isNothing.set(currentSize++);
          } else {
            double converted = convertBigIntegerToDouble(item);
            data[currentSize++] = Double.doubleToRawLongBits(converted);
          }
        }
      } else {
        throw new IllegalStateException(
            "Unexpected storage implementation for type BIG INTEGER: "
                + storage
                + ". This is a bug in the Table library.");
      }
    } else if (storage.getType() instanceof BooleanType) {
      if (storage instanceof BoolStorage boolStorage) {
        int n = boolStorage.size();
        for (int i = 0; i < n; i++) {
          if (boolStorage.isNothing(i)) {
            isNothing.set(currentSize++);
          } else {
            double x = ToFloatStorageConverter.booleanAsDouble(boolStorage.getItem(i));
            data[currentSize++] = Double.doubleToRawLongBits(x);
          }
        }
      } else {
        throw new IllegalStateException(
            "Unexpected storage implementation for type BOOLEAN: "
                + storage
                + ". This is a bug in the Table library.");
      }
    } else {
      throw new StorageTypeMismatchException(getType(), storage.getType());
    }
  }

  /**
   * Append a new double to this builder.
   *
   * @param data the double to append
   */
  public void appendDouble(double data) {
    if (currentSize >= this.data.length) {
      grow();
    }
    appendRawNoGrow(Double.doubleToRawLongBits(data));
  }

  /**
   * Append a new integer value to this builder, converting it to a double value.
   *
   * <p>It ensures that any loss of precision is reported.
   */
  public void appendLong(long integer) {
    if (currentSize >= this.data.length) {
      grow();
    }

    double converted = convertIntegerToDouble(integer);
    appendRawNoGrow(Double.doubleToRawLongBits(converted));
  }

  public void appendBigInteger(BigInteger integer) {
    if (currentSize >= this.data.length) {
      grow();
    }

    double converted = convertBigIntegerToDouble(integer);
    appendRawNoGrow(Double.doubleToRawLongBits(converted));
  }

  public void appendBigDecimal(BigDecimal integer) {
    if (currentSize >= this.data.length) {
      grow();
    }

    double converted = convertBigDecimalToDouble(integer);
    appendRawNoGrow(Double.doubleToRawLongBits(converted));
  }

  @Override
  public Storage<Double> seal() {
    return new DoubleStorage(data, currentSize, isNothing);
  }

  /**
   * Converts and `long` value into `double`.
   *
   * <p>It verifies if the integer can be exactly represented in a double, and if not, it reports a
   * warning.
   */
  protected double convertIntegerToDouble(long integer) {
    double floatingPointValue = (double) integer;
    boolean isLosingPrecision = (long) floatingPointValue != integer;
    if (isLosingPrecision) {
      precisionLossAggregator.reportIntegerPrecisionLoss(integer, floatingPointValue);
    }
    return floatingPointValue;
  }

  protected double convertBigIntegerToDouble(BigInteger bigInteger) {
    double floatingPointValue = bigInteger.doubleValue();
    BigInteger reconstructed = BigDecimal.valueOf(floatingPointValue).toBigInteger();
    boolean isLosingPrecision = !bigInteger.equals(reconstructed);
    if (isLosingPrecision) {
      precisionLossAggregator.reportIntegerPrecisionLoss(bigInteger, floatingPointValue);
    }
    return floatingPointValue;
  }

  protected double convertBigDecimalToDouble(BigDecimal bigDecimal) {
    double floatingPointValue = bigDecimal.doubleValue();
    if (Double.isInfinite(floatingPointValue)) {
      precisionLossAggregator.reportBigDecimalPrecisionLoss(bigDecimal, floatingPointValue);
    } else {
      BigDecimal reconstructed = BigDecimal.valueOf(floatingPointValue);
      boolean isLosingPrecision = !bigDecimal.equals(reconstructed);
      if (isLosingPrecision) {
        precisionLossAggregator.reportBigDecimalPrecisionLoss(bigDecimal, floatingPointValue);
      }
    }
    return floatingPointValue;
  }

  protected static class PrecisionLossAggregator extends ProblemAggregator {
    protected PrecisionLossAggregator(ProblemAggregator parent) {
      super(parent);
    }

    private LossOfIntegerPrecision integerInstance = null;
    private LossOfBigDecimalPrecision bigDecimalInstance = null;

    @Override
    public ProblemSummary summarize() {
      ProblemSummary summary = super.summarize();
      if (integerInstance != null) {
        summary.add(integerInstance);
      }
      if (bigDecimalInstance != null) {
        summary.add(bigDecimalInstance);
      }
      return summary;
    }

    final void reportIntegerPrecisionLoss(Number number, double approximation) {
      if (integerInstance == null) {
        integerInstance = new LossOfIntegerPrecision(number, approximation);
      } else {
        integerInstance.incrementAffectedRows();
      }
    }

    final void reportBigDecimalPrecisionLoss(BigDecimal number, double approximation) {
      if (bigDecimalInstance == null) {
        bigDecimalInstance = new LossOfBigDecimalPrecision(number, approximation);
      } else {
        bigDecimalInstance.incrementAffectedRows();
      }
    }
  }

  protected final PrecisionLossAggregator precisionLossAggregator;
}
