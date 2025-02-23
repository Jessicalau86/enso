package org.enso.interpreter.runtime.data.atom;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import org.enso.interpreter.node.ExpressionNode;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.data.hash.EnsoHashMap;
import org.enso.interpreter.runtime.data.hash.HashMapInsertAllNode;
import org.enso.interpreter.runtime.data.hash.HashMapSizeNode;
import org.enso.interpreter.runtime.type.TypesGen;
import org.enso.interpreter.runtime.warning.AppendWarningNode;
import org.enso.interpreter.runtime.warning.WarningsLibrary;

/**
 * A node instantiating a constant {@link AtomConstructor} with values computed based on the
 * children nodes.
 */
@NodeInfo(shortName = "Instantiate", description = "Instantiates a constant Atom constructor")
abstract class InstantiateNode extends ExpressionNode {
  final AtomConstructor constructor;
  private @Children ExpressionNode[] arguments;
  private @Child WarningsLibrary warnings = WarningsLibrary.getFactory().createDispatched(3);
  private @CompilationFinal(dimensions = 1) CountingConditionProfile[] profiles;
  private @CompilationFinal(dimensions = 1) CountingConditionProfile[] warningProfiles;
  private @CompilationFinal(dimensions = 1) BranchProfile[] sentinelProfiles;
  private final CountingConditionProfile anyWarningsProfile = CountingConditionProfile.create();

  InstantiateNode(AtomConstructor constructor, ExpressionNode[] arguments) {
    this.constructor = constructor;
    this.arguments = arguments;
    this.profiles = new CountingConditionProfile[arguments.length];
    this.sentinelProfiles = new BranchProfile[arguments.length];
    this.warningProfiles = new CountingConditionProfile[arguments.length];
    for (int i = 0; i < arguments.length; ++i) {
      this.profiles[i] = CountingConditionProfile.create();
      this.sentinelProfiles[i] = BranchProfile.create();
      this.warningProfiles[i] = CountingConditionProfile.create();
    }
  }

  /**
   * Creates an instance of this node.
   *
   * @param constructor the {@link AtomConstructor} this node will be instantiating
   * @param arguments the expressions that produce field values
   * @return a node that instantiates {@code constructor}
   */
  public static InstantiateNode build(AtomConstructor constructor, ExpressionNode[] arguments) {
    return InstantiateNodeGen.create(constructor, arguments);
  }

  /**
   * Executes the node, by executing all its children and putting their values as fields of the
   * newly created {@link AtomConstructor} instance.
   *
   * @param frame the stack frame for execution
   * @return the newly created {@link AtomConstructor} instance.
   */
  @Specialization
  @ExplodeLoop
  Object doExecute(
      VirtualFrame frame,
      @Cached(parameters = {"constructor"}) AtomConstructorInstanceNode createInstanceNode,
      @Cached AppendWarningNode appendWarningNode,
      @Cached HashMapSizeNode mapSizeNode,
      @Cached HashMapInsertAllNode mapInsertAllNode) {
    Object[] argumentValues = new Object[arguments.length];
    boolean anyWarnings = false;
    var accumulatedWarnings = EnsoHashMap.empty();
    for (int i = 0; i < arguments.length; i++) {
      CountingConditionProfile profile = profiles[i];
      CountingConditionProfile warningProfile = warningProfiles[i];
      BranchProfile sentinelProfile = sentinelProfiles[i];
      Object argument = arguments[i].executeGeneric(frame);
      if (profile.profile(TypesGen.isDataflowError(argument))) {
        return argument;
      } else if (warningProfile.profile(warnings.hasWarnings(argument))) {
        anyWarnings = true;
        try {
          var argumentWarnsMap = warnings.getWarnings(argument, false);
          var maxWarningsToAdd =
              EnsoContext.get(this).getWarningsLimit() - mapSizeNode.execute(accumulatedWarnings);
          accumulatedWarnings =
              mapInsertAllNode.executeInsertAll(
                  frame, accumulatedWarnings, argumentWarnsMap, maxWarningsToAdd);
          argumentValues[i] = warnings.removeWarnings(argument);
        } catch (UnsupportedMessageException e) {
          throw EnsoContext.get(this).raiseAssertionPanic(this, null, e);
        }
      } else if (TypesGen.isPanicSentinel(argument)) {
        sentinelProfile.enter();
        throw TypesGen.asPanicSentinel(argument);
      } else {
        argumentValues[i] = argument;
      }
    }
    if (anyWarningsProfile.profile(anyWarnings)) {
      return appendWarningNode.executeAppend(
          frame, createInstanceNode.execute(argumentValues), accumulatedWarnings);
    } else {
      return createInstanceNode.execute(argumentValues);
    }
  }
}
