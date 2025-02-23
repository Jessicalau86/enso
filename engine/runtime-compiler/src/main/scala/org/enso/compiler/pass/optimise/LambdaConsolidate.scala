package org.enso.compiler.pass.optimise

import org.enso.compiler.context.{FreshNameSupply, InlineContext, ModuleContext}
import org.enso.compiler.core.Implicits.AsMetadata
import org.enso.compiler.core.{CompilerError, IR, Identifier}
import org.enso.compiler.core.ir.{
  DefinitionArgument,
  Empty,
  Expression,
  Function,
  IdentifiedLocation,
  Location,
  Module,
  Name
}
import org.enso.compiler.core.ir.expression.warnings
import org.enso.compiler.core.ir.expression.errors
import org.enso.compiler.pass.IRPass
import org.enso.compiler.pass.analyse.alias.graph.{
  GraphOccurrence,
  Graph => AliasGraph
}
import org.enso.compiler.pass.analyse.{
  AliasAnalysis,
  DataflowAnalysis,
  DemandAnalysis,
  TailCall
}
import org.enso.compiler.pass.analyse.alias.{AliasMetadata => AliasInfo}
import org.enso.compiler.pass.desugar._
import org.enso.compiler.pass.resolve.IgnoredBindings

import java.util.UUID

/** This pass consolidates chains of lambdas into multi-argument lambdas
  * internally.
  *
  * Enso's syntax, due to its unified design, only supports single-argument
  * lambda expressions. However, internally, we want to be able to use
  * multi-argument lambda expressions for performance reasons. This pass turns
  * these chains of lambda expressions into multi-argument lambdas.
  *
  * That means that code like this:
  *
  * {{{
  *   x -> y -> z -> ...
  * }}}
  *
  * Is translated to an internal representation equivalent to
  *
  * {{{
  *   x y z -> ...
  * }}}
  *
  * Please note that this pass invalidates _all_ metdata on the transformed
  * portions of the program, and hence must be run before the deeper analysis
  * passes.
  *
  * This pass requires the context to provide:
  *
  * - A [[FreshNameSupply]].
  */
case object LambdaConsolidate extends IRPass {
  override type Metadata = IRPass.Metadata.Empty
  override type Config   = IRPass.Configuration.Default

  override lazy val precursorPasses: Seq[IRPass] = List(
    AliasAnalysis,
    ComplexType,
    FunctionBinding,
    GenerateMethodBodies,
    IgnoredBindings,
    LambdaShorthandToLambda,
    OperatorToFunction,
    SectionsToBinOp
  )
  override lazy val invalidatedPasses: Seq[IRPass] = List(
    AliasAnalysis,
    DataflowAnalysis,
    DemandAnalysis,
    TailCall
  )

  /** Performs lambda consolidation on a module.
    *
    * @param ir the Enso IR to process
    * @param moduleContext a context object that contains the information needed
    *                      to process a module
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runModule(
    ir: Module,
    moduleContext: ModuleContext
  ): Module =
    ir.mapExpressions(
      runExpression(
        _,
        new InlineContext(
          moduleContext,
          freshNameSupply = moduleContext.freshNameSupply,
          compilerConfig  = moduleContext.compilerConfig
        )
      )
    )

  /** Performs lambda consolidation on an expression.
    *
    * @param ir the Enso IR to process
    * @param inlineContext a context object that contains the information needed
    *                      for inline evaluation
    * @return `ir`, possibly having made transformations or annotations to that
    *         IR.
    */
  override def runExpression(
    ir: Expression,
    inlineContext: InlineContext
  ): Expression = {
    val freshNameSupply = inlineContext.freshNameSupply.getOrElse(
      throw new CompilerError(
        "A fresh name supply is required for lambda consolidation."
      )
    )
    ir.transformExpressions { case fn: Function =>
      collapseFunction(fn, inlineContext, freshNameSupply)
    }
  }

  /** Collapses chained lambdas for a function definition where possible.
    *
    * @param function the function definition to optimise
    * @return the optimised version of `function`, with any directly chained
    *         lambdas collapsed
    */
  private def collapseFunction(
    function: Function,
    inlineContext: InlineContext,
    freshNameSupply: FreshNameSupply
  ): Function = {
    function match {
      case lam @ Function.Lambda(_, body, _, _, _, _) =>
        val chainedLambdas = lam :: gatherChainedLambdas(body)
        val chainedArgList =
          chainedLambdas.foldLeft(List[DefinitionArgument]())(
            _ ::: _.arguments
          )
        val lastBody = chainedLambdas.last.body

        val shadowedBindingIds = getShadowedBindingIds(chainedArgList)

        val argIsShadowed = chainedArgList.map {
          case spec: DefinitionArgument.Specified =>
            val aliasInfo = spec
              .unsafeGetMetadata(
                AliasAnalysis,
                "Missing aliasing information for an argument definition"
              )
              .unsafeAs[AliasInfo.Occurrence]
            shadowedBindingIds.contains(aliasInfo.id)
        }

        val argsWithShadowed = attachShadowingWarnings(
          chainedArgList.zip(argIsShadowed)
        )
        val usageIdsForShadowed = usageIdsForShadowedArgs(argsWithShadowed)

        val newArgNames = generateNewNames(argsWithShadowed, freshNameSupply)

        val (processedArgList, newBody) =
          computeReplacedExpressions(newArgNames, lastBody, usageIdsForShadowed)

        val consolidatedArgs = processedArgList.map(
          _.mapExpressions(runExpression(_, inlineContext))
        )

        val newLocation = chainedLambdas.head.location match {
          case Some(location) =>
            Some(
              IdentifiedLocation.create(
                new Location(
                  location.start,
                  chainedLambdas.last.location.getOrElse(location).location.end
                ),
                location.id
              )
            )
          case None => None
        }

        lam.copy(
          arguments = consolidatedArgs,
          body      = runExpression(newBody, inlineContext),
          location  = newLocation,
          canBeTCO  = chainedLambdas.last.canBeTCO
        )
      case _: Function.Binding =>
        throw new CompilerError(
          "Function sugar should not be present during lambda consolidation."
        )
    }
  }

  /** Attaches warnings to function parameters that are shadowed.
    *
    * These warnings contain the IR that is shadowing the parameter, as well as
    * the original name of the parameter.
    *
    * @param argsWithShadowed the arguments, with whether or not they are
    *                         shadowed
    * @return the list of arguments, some with attached warnings, along with
    *         whether or not they are shadowed
    */
  private def attachShadowingWarnings(
    argsWithShadowed: List[(DefinitionArgument, Boolean)]
  ): List[(DefinitionArgument, Boolean)] = {
    val args = argsWithShadowed.map(_._1)
    val argsWithIndex =
      argsWithShadowed.zipWithIndex.map(t => (t._1._1, t._1._2, t._2))

    argsWithIndex.map { case (arg, isShadowed, ix) =>
      if (isShadowed) {
        val restArgs = args.drop(ix + 1)
        arg match {
          case spec @ DefinitionArgument.Specified(argName, _, _, _, _, _) =>
            val mShadower = restArgs.collectFirst {
              case s @ DefinitionArgument.Specified(sName, _, _, _, _, _)
                  if sName.name == argName.name =>
                s
            }

            val shadower: IR = mShadower.getOrElse(Empty(spec.location))

            spec.getDiagnostics.add(
              warnings.Shadowed
                .FunctionParam(argName.name, shadower, spec.location)
            )

            (spec, isShadowed)
        }
      } else {
        (arg, isShadowed)
      }
    }
  }

  /** Generates a list of all the lambdas directly chained in the provided
    * function body.
    *
    * @param body the function body to optimise
    * @return the directly chained lambdas in `body`
    */
  private def gatherChainedLambdas(body: Expression): List[Function.Lambda] = {
    body match {
      case Expression.Block(expressions, lam: Function.Lambda, _, _, _)
          if expressions.isEmpty =>
        lam :: gatherChainedLambdas(lam.body)
      case l @ Function.Lambda(_, body, _, _, _, _) =>
        l :: gatherChainedLambdas(body)
      case _ => List()
    }
  }

  /** Replaces all usages of an argument name in the function argument defaults
    * and the function body.
    *
    * @param body the function body
    * @param defaults the function argument defaults
    * @param argument the argument to replace occurrences with
    * @param toReplaceExpressionIds the identifiers of expressions needing
    *                               replacemebt
    * @return `body` and `defaults` with any occurrence of the old name replaced
    *        by the new name
    */
  private def replaceUsages(
    body: Expression,
    defaults: List[Option[Expression]],
    argument: DefinitionArgument,
    toReplaceExpressionIds: Set[UUID @Identifier]
  ): (Expression, List[Option[Expression]]) = {
    (
      replaceInExpression(body, argument, toReplaceExpressionIds),
      defaults.map(
        _.map(replaceInExpression(_, argument, toReplaceExpressionIds))
      )
    )
  }

  /** Replaces usages of a name in an expression.
    *
    * As usages of a name can only be an [[Name]], we can safely use the
    * expression transformation mechanism to do this.
    *
    * @param expr the expression to replace usages in
    * @param argument the argument whose usages are being replaced
    * @param toReplaceExpressionIds the identifiers of expressions that need to
    *                               be replaced
    * @return `expr`, with occurrences of the symbol for `argument` replaced
    */
  private def replaceInExpression(
    expr: Expression,
    argument: DefinitionArgument,
    toReplaceExpressionIds: Set[UUID @Identifier]
  ): Expression = {
    expr.transformExpressions { case name: Name =>
      replaceInName(name, argument, toReplaceExpressionIds)
    }
  }

  /** Replaces a name occurrence with a new name.
    *
    * @param name the IR name to replace the symbol in
    * @param argument the argument to replace the symbol in `name` with
    * @param toReplaceExpressionIds the identifiers of expressions that need
    *                               replacement
    * @return `name`, with the symbol replaced by `argument.name`
    */
  private def replaceInName(
    name: Name,
    argument: DefinitionArgument,
    toReplaceExpressionIds: Set[UUID @Identifier]
  ): Name = {
    if (toReplaceExpressionIds.contains(name.getId)) {
      name match {
        case spec: Name.Literal =>
          spec.copy(
            name = argument match {
              case defSpec: DefinitionArgument.Specified => defSpec.name.name
            }
          )
        case self: Name.Self             => self
        case selfType: Name.SelfType     => selfType
        case special: Name.Special       => special
        case blank: Name.Blank           => blank
        case ref: Name.MethodReference   => ref
        case qual: Name.Qualified        => qual
        case err: errors.Resolution      => err
        case err: errors.Conversion      => err
        case annotation: Name.Annotation => annotation
      }
    } else {
      name
    }
  }

  /** Computes the set of aliasing identifiers shadowed by the argument
    * definitions.
    *
    * @param args the consolidated list of function arguments
    * @return the set of aliasing identifiers shadowed by `args`
    */
  private def getShadowedBindingIds(
    args: List[DefinitionArgument]
  ): Set[AliasGraph.Id] = {
    args
      .map { case spec: DefinitionArgument.Specified =>
        val aliasInfo =
          spec
            .unsafeGetMetadata(
              AliasAnalysis,
              "Missing aliasing information for an argument definition."
            )
            .unsafeAs[AliasInfo.Occurrence]
        aliasInfo.graph
          .getOccurrence(aliasInfo.id)
          .flatMap(occ => Some(aliasInfo.graph.knownShadowedDefinitions(occ)))
          .getOrElse(Set())
      }
      .foldLeft(Set[GraphOccurrence]())(_ ++ _)
      .map(_.id)
  }

  /** Computes the identifiers of expression that use a shadowed argument.
    *
    * @param argsWithShadowed the argument definitions with whether or not they
    *                         are shadowed
    * @return the set of usage IR identifiers for each shadowed argument, where
    *         an empty set represents a non-shadowed argument
    */
  private def usageIdsForShadowedArgs(
    argsWithShadowed: List[(DefinitionArgument, Boolean)]
  ): List[Set[UUID @Identifier]] = {
    argsWithShadowed.map {
      case (spec: DefinitionArgument.Specified, isShadowed) =>
        val aliasInfo =
          spec
            .unsafeGetMetadata(
              AliasAnalysis,
              "Missing aliasing information for an argument definition."
            )
            .unsafeAs[AliasInfo.Occurrence]

        // Empty set is used to indicate that it isn't shadowed
        val usageIds =
          if (isShadowed) {
            aliasInfo.graph
              .linksFor(aliasInfo.id)
              .filter(_.target == aliasInfo.id)
              .map(link => aliasInfo.graph.getOccurrence(link.source))
              .collect {
                case Some(
                      GraphOccurrence.Use(_, _, identifier, _)
                    ) =>
                  identifier
              }
          } else Set[UUID @Identifier]()

        usageIds
    }
  }

  /** Generates new names for the arguments that have been shadowed.
    *
    * @param argsWithShadowed the args with whether or not they are shadowed
    * @return a set of argument names, with shadowed arguments replaced
    */
  private def generateNewNames(
    argsWithShadowed: List[(DefinitionArgument, Boolean)],
    freshNameSupply: FreshNameSupply
  ): List[DefinitionArgument] = {
    argsWithShadowed.map {
      case (
            spec: DefinitionArgument.Specified,
            isShadowed
          ) =>
        val oldName = spec.name
        val newName =
          if (isShadowed) {
            freshNameSupply
              .newName(from = Some(oldName))
              .copy(
                location    = oldName.location,
                passData    = oldName.passData,
                diagnostics = oldName.diagnostics,
                id          = oldName.getId
              )
          } else oldName

        spec.copy(name = newName)
    }
  }

  /** Computes the new arguments and new function body, replacing occurrences of
    * renamed names as needed.
    *
    * @param args the arguments (already renamed)
    * @param body the function body
    * @param usageIdsForShadowed the identifiers for usages of shadowed names
    * @return `args` and `body`, with any usages of shadowed symbols replaced
    */
  private def computeReplacedExpressions(
    args: List[DefinitionArgument],
    body: Expression,
    usageIdsForShadowed: List[Set[UUID @Identifier]]
  ): (List[DefinitionArgument], Expression) = {
    var newBody     = body
    var newDefaults = args.map(_.defaultValue)

    val namesNeedingReplacement =
      args.zip(usageIdsForShadowed).filterNot(x => x._2.isEmpty)

    for ((arg, idents) <- namesNeedingReplacement) {
      val (updatedBody, updatedDefaults) =
        replaceUsages(newBody, newDefaults, arg, idents)

      newBody     = updatedBody
      newDefaults = updatedDefaults
    }

    val processedArgList = args.zip(newDefaults).map {
      case (spec: DefinitionArgument.Specified, default) =>
        spec.copy(defaultValue = default)
    }

    (processedArgList, newBody)
  }
}
