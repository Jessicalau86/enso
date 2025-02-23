package org.enso.interpreter.node.callable.argument;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.enso.interpreter.EnsoLanguage;
import org.enso.interpreter.node.BaseNode.TailStatus;
import org.enso.interpreter.node.EnsoRootNode;
import org.enso.interpreter.node.ExpressionNode;
import org.enso.interpreter.node.callable.ApplicationNode;
import org.enso.interpreter.node.callable.InvokeCallableNode.DefaultsExecutionMode;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.node.expression.builtin.meta.AtomWithAHoleNode;
import org.enso.interpreter.node.expression.builtin.meta.IsValueOfTypeNode;
import org.enso.interpreter.node.expression.literal.LiteralNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.UnresolvedConstructor;
import org.enso.interpreter.runtime.callable.UnresolvedConversion;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition;
import org.enso.interpreter.runtime.callable.argument.ArgumentDefinition.ExecutionMode;
import org.enso.interpreter.runtime.callable.argument.CallArgument;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.callable.function.FunctionSchema;
import org.enso.interpreter.runtime.data.EnsoMultiValue;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.error.DataflowError;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.error.PanicSentinel;
import org.enso.interpreter.runtime.library.dispatch.TypeOfNode;
import org.enso.interpreter.runtime.library.dispatch.TypesLibrary;
import org.enso.interpreter.runtime.util.CachingSupplier;
import org.graalvm.collections.Pair;

public abstract class ReadArgumentCheckNode extends Node {
  private final String comment;
  @CompilerDirectives.CompilationFinal private String expectedTypeMessage;

  ReadArgumentCheckNode(String comment) {
    this.comment = comment;
  }

  /** */
  public static ExpressionNode wrap(ExpressionNode original, ReadArgumentCheckNode check) {
    return new TypeCheckExpressionNode(original, check);
  }

  /**
   * Executes check or conversion of the value.
   *
   * @param frame frame requesting the conversion
   * @param value the value to convert
   * @return {@code null} when the check isn't satisfied and conversion isn't possible or non-{@code
   *     null} value that can be used as a result
   */
  public final Object handleCheckOrConversion(VirtualFrame frame, Object value) {
    var result = executeCheckOrConversion(frame, value);
    if (result == null) {
      throw panicAtTheEnd(value);
    }
    return result;
  }

  abstract Object findDirectMatch(VirtualFrame frame, Object value);

  abstract Object executeCheckOrConversion(VirtualFrame frame, Object value);

  abstract String expectedTypeMessage();

  protected final String joinTypeParts(List<String> parts, String separator) {
    assert !parts.isEmpty();
    if (parts.size() == 1) {
      return parts.get(0);
    }

    var separatorWithSpace = " " + separator + " ";
    var builder = new StringBuilder();
    boolean isFirst = true;
    for (String part : parts) {
      if (isFirst) {
        isFirst = false;
      } else {
        builder.append(separatorWithSpace);
      }

      // If the part contains a space, it means it is not a single type but already a more complex
      // expression with a separator.
      // So to ensure we don't mess up the expression layers, we need to add parentheses around it.
      boolean needsParentheses = part.contains(" ");
      if (needsParentheses) {
        builder.append("(");
      }
      builder.append(part);
      if (needsParentheses) {
        builder.append(")");
      }
    }

    return builder.toString();
  }

  final PanicException panicAtTheEnd(Object v) {
    if (expectedTypeMessage == null) {
      CompilerDirectives.transferToInterpreterAndInvalidate();
      expectedTypeMessage = expectedTypeMessage();
    }
    var ctx = EnsoContext.get(this);
    Text msg;
    if (v instanceof UnresolvedConstructor) {
      msg = Text.create("Cannot find constructor {got} among {exp}");
    } else {
      var where = Text.create(comment == null ? "expression" : comment);
      var exp = Text.create("expected ");
      var got = Text.create(" to be {exp}, but got {got}");
      msg = Text.create(exp, Text.create(where, got));
    }
    var err = ctx.getBuiltins().error().makeTypeErrorOfComment(expectedTypeMessage, v, msg);
    throw new PanicException(err, this);
  }

  public static ReadArgumentCheckNode allOf(String argumentName, ReadArgumentCheckNode... checks) {
    var list = Arrays.asList(checks);
    var flatten =
        list.stream()
            .flatMap(
                n -> n instanceof AllOfNode all ? Arrays.asList(all.checks).stream() : Stream.of(n))
            .toList();
    var arr = toArray(flatten);
    return switch (arr.length) {
      case 0 -> null;
      case 1 -> arr[0];
      default -> new AllOfNode(argumentName, arr);
    };
  }

  public static ReadArgumentCheckNode oneOf(String comment, List<ReadArgumentCheckNode> checks) {
    var arr = toArray(checks);
    return switch (arr.length) {
      case 0 -> null;
      case 1 -> arr[0];
      default -> new OneOfNode(comment, arr);
    };
  }

  public static ReadArgumentCheckNode build(EnsoContext ctx, String comment, Type expectedType) {
    assert ctx.getBuiltins().any() != expectedType : "Don't check for Any: " + expectedType;
    return ReadArgumentCheckNodeFactory.TypeCheckNodeGen.create(comment, expectedType);
  }

  public static ReadArgumentCheckNode meta(
      String comment, Supplier<? extends Object> metaObjectSupplier) {
    var cachingSupplier = CachingSupplier.wrap(metaObjectSupplier);
    return ReadArgumentCheckNodeFactory.MetaCheckNodeGen.create(comment, cachingSupplier);
  }

  public static boolean isWrappedThunk(Function fn) {
    if (fn.getSchema() == LazyCheckRootNode.SCHEMA) {
      return fn.getPreAppliedArguments()[0] instanceof Function wrappedFn && wrappedFn.isThunk();
    }
    return false;
  }

  private static boolean isAllFitValue(Object v) {
    return v instanceof DataflowError || AtomWithAHoleNode.isHole(v);
  }

  private static ReadArgumentCheckNode[] toArray(List<ReadArgumentCheckNode> list) {
    if (list == null) {
      return new ReadArgumentCheckNode[0];
    }
    var cnt = (int) list.stream().filter(n -> n != null).count();
    var arr = new ReadArgumentCheckNode[cnt];
    var it = list.iterator();
    for (int i = 0; i < cnt; ) {
      var element = it.next();
      if (element != null) {
        arr[i++] = element;
      }
    }
    return arr;
  }

  static final class AllOfNode extends ReadArgumentCheckNode {
    @Children private ReadArgumentCheckNode[] checks;
    @Child private TypesLibrary types;

    AllOfNode(String name, ReadArgumentCheckNode[] checks) {
      super(name);
      this.checks = checks;
      this.types = TypesLibrary.getFactory().createDispatched(checks.length);
    }

    @Override
    Object findDirectMatch(VirtualFrame frame, Object value) {
      return null;
    }

    @Override
    @ExplodeLoop
    Object executeCheckOrConversion(VirtualFrame frame, Object value) {
      var values = new Object[checks.length];
      var valueTypes = new Type[checks.length];
      var at = 0;
      for (var n : checks) {
        var result = n.executeCheckOrConversion(frame, value);
        if (result == null) {
          return null;
        }
        values[at] = result;
        valueTypes[at] = types.getType(result);
        at++;
      }
      return EnsoMultiValue.create(valueTypes, values);
    }

    @Override
    String expectedTypeMessage() {
      var parts =
          Arrays.stream(checks)
              .map(ReadArgumentCheckNode::expectedTypeMessage)
              .collect(Collectors.toList());
      return joinTypeParts(parts, "&");
    }
  }

  static final class OneOfNode extends ReadArgumentCheckNode {
    @Children private ReadArgumentCheckNode[] checks;

    OneOfNode(String name, ReadArgumentCheckNode[] checks) {
      super(name);
      this.checks = checks;
    }

    @Override
    @ExplodeLoop
    final Object findDirectMatch(VirtualFrame frame, Object value) {
      for (var n : checks) {
        var result = n.findDirectMatch(frame, value);
        if (result != null) {
          return result;
        }
      }
      return null;
    }

    @Override
    @ExplodeLoop
    Object executeCheckOrConversion(VirtualFrame frame, Object value) {
      var direct = findDirectMatch(frame, value);
      if (direct != null) {
        return direct;
      }
      for (var n : checks) {
        var result = n.executeCheckOrConversion(frame, value);
        if (result != null) {
          return result;
        }
      }
      return null;
    }

    @Override
    String expectedTypeMessage() {
      var parts =
          Arrays.stream(checks)
              .map(ReadArgumentCheckNode::expectedTypeMessage)
              .collect(Collectors.toList());
      return joinTypeParts(parts, "|");
    }
  }

  abstract static class TypeCheckNode extends ReadArgumentCheckNode {
    private final Type expectedType;
    @Child IsValueOfTypeNode checkType;
    @CompilerDirectives.CompilationFinal private String expectedTypeMessage;
    @CompilerDirectives.CompilationFinal private LazyCheckRootNode lazyCheck;
    @Child private EnsoMultiValue.CastToNode castTo;

    TypeCheckNode(String name, Type expectedType) {
      super(name);
      this.checkType = IsValueOfTypeNode.build();
      this.expectedType = expectedType;
    }

    @Specialization
    Object doPanicSentinel(VirtualFrame frame, PanicSentinel panicSentinel) {
      throw panicSentinel;
    }

    @Specialization
    Object doUnresolvedConstructor(
        VirtualFrame frame,
        UnresolvedConstructor unresolved,
        @Cached UnresolvedConstructor.ConstructNode construct) {
      var state = Function.ArgumentsHelper.getState(frame.getArguments());
      return construct.execute(frame, state, expectedType, unresolved);
    }

    @Specialization(rewriteOn = InvalidAssumptionException.class)
    Object doCheckNoConversionNeeded(VirtualFrame frame, Object v)
        throws InvalidAssumptionException {
      var ret = findDirectMatch(frame, v);
      if (ret != null) {
        return ret;
      } else {
        throw new InvalidAssumptionException();
      }
    }

    @Specialization(
        limit = "10",
        guards = {"cachedType != null", "findType(typeOfNode, v, cachedType) == cachedType"})
    Object doWithConversionCached(
        VirtualFrame frame,
        Object v,
        @Shared("typeOfNode") @Cached TypeOfNode typeOfNode,
        @Cached(value = "findType(typeOfNode, v)", dimensions = 1) Type[] cachedType,
        @Cached("findConversionNode(cachedType)") ApplicationNode convertNode) {
      return handleWithConversion(frame, v, convertNode);
    }

    @Specialization(replaces = "doWithConversionCached")
    Object doWithConversionUncached(
        VirtualFrame frame, Object v, @Shared("typeOfNode") @Cached TypeOfNode typeOfNode) {
      var type = findType(typeOfNode, v);
      return doWithConversionUncachedBoundary(frame == null ? null : frame.materialize(), v, type);
    }

    @ExplodeLoop
    final Object findDirectMatch(VirtualFrame frame, Object v) {
      if (isAllFitValue(v)) {
        return v;
      }
      if (v instanceof Function fn && fn.isThunk()) {
        if (lazyCheck == null) {
          CompilerDirectives.transferToInterpreter();
          var enso = EnsoLanguage.get(this);
          var node = (ReadArgumentCheckNode) copy();
          lazyCheck = new LazyCheckRootNode(enso, node);
        }
        var lazyCheckFn = lazyCheck.wrapThunk(fn);
        return lazyCheckFn;
      }
      if (v instanceof EnsoMultiValue mv) {
        if (castTo == null) {
          CompilerDirectives.transferToInterpreter();
          castTo = insert(EnsoMultiValue.CastToNode.create());
        }
        var result = castTo.executeCast(expectedType, mv);
        if (result != null) {
          return result;
        }
      }
      if (checkType.execute(expectedType, v)) {
        return v;
      }
      return null;
    }

    private Pair<Function, Type> findConversion(Type from) {
      if (expectedType == from) {
        return null;
      }
      var ctx = EnsoContext.get(this);

      if (getRootNode() instanceof EnsoRootNode root) {
        var convert = UnresolvedConversion.build(root.getModuleScope());
        var conv = convert.resolveFor(ctx, expectedType, from);
        if (conv != null) {
          return Pair.create(conv, expectedType);
        }
      }
      return null;
    }

    ApplicationNode findConversionNode(Type[] allTypes) {
      if (allTypes == null) {
        allTypes = new Type[] {null};
      }
      for (var from : allTypes) {
        var convAndType = findConversion(from);

        if (convAndType != null) {
          if (NodeUtil.findParent(this, ReadArgumentNode.class) instanceof ReadArgumentNode ran) {
            CompilerAsserts.neverPartOfCompilation();
            var convNode = LiteralNode.build(convAndType.getLeft());
            var intoNode = LiteralNode.build(convAndType.getRight());
            var valueNode = ran.plainRead();
            var args =
                new CallArgument[] {
                  new CallArgument(null, intoNode), new CallArgument(null, valueNode)
                };
            return ApplicationNode.build(convNode, args, DefaultsExecutionMode.EXECUTE);
          } else if (NodeUtil.findParent(this, TypeCheckExpressionNode.class)
              instanceof TypeCheckExpressionNode tcen) {
            CompilerAsserts.neverPartOfCompilation();
            var convNode = LiteralNode.build(convAndType.getLeft());
            var intoNode = LiteralNode.build(convAndType.getRight());
            var valueNode = tcen.original;
            var args =
                new CallArgument[] {
                  new CallArgument(null, intoNode), new CallArgument(null, valueNode)
                };
            return ApplicationNode.build(convNode, args, DefaultsExecutionMode.EXECUTE);
          }
        }
      }
      return null;
    }

    Type[] findType(TypeOfNode typeOfNode, Object v) {
      return findType(typeOfNode, v, null);
    }

    Type[] findType(TypeOfNode typeOfNode, Object v, Type[] previous) {
      if (v instanceof EnsoMultiValue multi) {
        return multi.allTypes();
      }
      if (v instanceof UnresolvedConstructor) {
        return null;
      }
      if (typeOfNode.execute(v) instanceof Type from) {
        if (previous != null && previous.length == 1 && previous[0] == from) {
          return previous;
        } else {
          return new Type[] {from};
        }
      }
      return null;
    }

    private Object handleWithConversion(VirtualFrame frame, Object v, ApplicationNode convertNode)
        throws PanicException {
      if (convertNode == null) {
        var ret = findDirectMatch(frame, v);
        if (ret != null) {
          return ret;
        }
        return null;
      } else {
        var converted = convertNode.executeGeneric(frame);
        return converted;
      }
    }

    @CompilerDirectives.TruffleBoundary
    private Object doWithConversionUncachedBoundary(
        MaterializedFrame frame, Object v, Type[] type) {
      var convertNode = findConversionNode(type);
      return handleWithConversion(frame, v, convertNode);
    }

    @Override
    String expectedTypeMessage() {
      if (expectedTypeMessage != null) {
        return expectedTypeMessage;
      }
      CompilerDirectives.transferToInterpreterAndInvalidate();
      expectedTypeMessage = expectedType.toString();
      return expectedTypeMessage;
    }
  }

  abstract static class MetaCheckNode extends ReadArgumentCheckNode {
    private final CachingSupplier<? extends Object> expectedSupplier;
    @CompilerDirectives.CompilationFinal private String expectedTypeMessage;

    MetaCheckNode(String name, CachingSupplier<? extends Object> expectedMetaSupplier) {
      super(name);
      this.expectedSupplier = expectedMetaSupplier;
    }

    @Override
    Object findDirectMatch(VirtualFrame frame, Object value) {
      return executeCheckOrConversion(frame, value);
    }

    @Specialization()
    Object verifyMetaObject(VirtualFrame frame, Object v, @Cached IsValueOfTypeNode isA) {
      if (isAllFitValue(v)) {
        return v;
      }
      if (isA.execute(expectedSupplier.get(), v)) {
        return v;
      } else {
        return null;
      }
    }

    @Override
    String expectedTypeMessage() {
      if (expectedTypeMessage != null) {
        return expectedTypeMessage;
      }
      CompilerDirectives.transferToInterpreterAndInvalidate();
      var iop = InteropLibrary.getUncached();
      try {
        expectedTypeMessage = iop.asString(iop.getMetaQualifiedName(expectedSupplier.get()));
      } catch (UnsupportedMessageException ex) {
        expectedTypeMessage = expectedSupplier.get().toString();
      }
      return expectedTypeMessage;
    }
  }

  private static final class LazyCheckRootNode extends RootNode {

    @Child private ThunkExecutorNode evalThunk;
    @Child private ReadArgumentCheckNode check;

    static final FunctionSchema SCHEMA =
        FunctionSchema.newBuilder()
            .argumentDefinitions(
                new ArgumentDefinition(0, "delegate", null, null, ExecutionMode.EXECUTE))
            .hasPreapplied(true)
            .build();

    LazyCheckRootNode(TruffleLanguage<?> language, ReadArgumentCheckNode check) {
      super(language);
      this.check = check;
      this.evalThunk = ThunkExecutorNode.build();
    }

    Function wrapThunk(Function thunk) {
      return new Function(getCallTarget(), thunk.getScope(), SCHEMA, new Object[] {thunk}, null);
    }

    @Override
    public Object execute(VirtualFrame frame) {
      var state = Function.ArgumentsHelper.getState(frame.getArguments());
      var args = Function.ArgumentsHelper.getPositionalArguments(frame.getArguments());
      assert args.length == 1;
      assert args[0] instanceof Function fn && fn.isThunk();
      var raw = evalThunk.executeThunk(frame, args[0], state, TailStatus.NOT_TAIL);
      var result = check.handleCheckOrConversion(frame, raw);
      return result;
    }
  }

  private static final class TypeCheckExpressionNode extends ExpressionNode {
    @Child private ExpressionNode original;
    @Child private ReadArgumentCheckNode check;

    TypeCheckExpressionNode(ExpressionNode original, ReadArgumentCheckNode check) {
      this.check = check;
      this.original = original;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
      var value = original.executeGeneric(frame);
      var result = check.handleCheckOrConversion(frame, value);
      return result;
    }

    @Override
    public boolean isInstrumentable() {
      return false;
    }
  }
}
