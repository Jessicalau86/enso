package org.enso.compiler.core.ir
package expression

import org.enso.compiler.core.Implicits.{ShowPassData, ToStringHelper}
import org.enso.compiler.core.{IR, Identifier}

import java.util.UUID
import scala.jdk.FunctionConverters.enrichAsScalaFromFunction

/** All function applications in Enso. */
trait Application extends Expression

object Application {

  /** A standard prefix function application.
    *
    * @param function the function being called
    * @param arguments the arguments to the function being called
    * @param hasDefaultsSuspended whether the function application has any
    * argument defaults in `function` suspended
    * @param location the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Prefix(
    function: Expression,
    arguments: List[CallArgument],
    hasDefaultsSuspended: Boolean,
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Application
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Create a prefix application.
      *
      * @param function the function being called
      * @param arguments the arguments to the function being called
      * @param hasDefaultsSuspended whether the function application has any
      * argument defaults in `function` suspended
      * @param location the source location that the node corresponds to
      * @param passData the pass metadata associated with this node
      * @param diagnostics the compiler diagnostics
      */
    def this(
      function: Expression,
      arguments: List[CallArgument],
      hasDefaultsSuspended: Boolean,
      location: Option[IdentifiedLocation],
      passData: MetadataStorage,
      diagnostics: DiagnosticStorage
    ) = {
      this(
        function,
        arguments,
        hasDefaultsSuspended,
        location,
        passData
      )
      this.diagnostics = diagnostics
    }

    /** Creates a copy of `this`.
      *
      * @param function             the function being called
      * @param arguments            the arguments to the function being called
      * @param hasDefaultsSuspended whether the function application has any
      *                             argument defaults in `function` suspended
      * @param location             the source location that the node corresponds to
      * @param passData             the pass metadata associated with this node
      * @param diagnostics          compiler diagnostics for this node
      * @param id                   the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      function: Expression                 = function,
      arguments: List[CallArgument]        = arguments,
      hasDefaultsSuspended: Boolean        = hasDefaultsSuspended,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Prefix = {
      if (
        function != this.function
        || arguments != this.arguments
        || hasDefaultsSuspended != this.hasDefaultsSuspended
        || location != this.location
        || passData != this.passData
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res =
          Prefix(
            function,
            arguments,
            hasDefaultsSuspended,
            location,
            passData
          )
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Prefix =
      copy(
        function = function.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        arguments = arguments.map(
          _.duplicate(
            keepLocations,
            keepMetadata,
            keepDiagnostics,
            keepIdentifiers
          )
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Prefix =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Prefix = {
      copy(function = fn(function), arguments.map(_.mapExpressions(fn)))
    }

    /** String representation. */
    override def toString: String =
      s"""
         |Application.Prefix(
         |function = $function,
         |arguments = $arguments,
         |hasDefaultsSuspended = $hasDefaultsSuspended,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = function :: arguments

    /** @inheritdoc */
    override def showCode(indent: Int): String = {
      val argStr = arguments.map(_.showCode(indent)).mkString(" ")

      s"((${function.showCode(indent)}) $argStr)"
    }
  }

  /** A representation of a term that is explicitly forced.
    *
    * @param target the expression being forced
    * @param location the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Force(
    target: Expression,
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Application
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    /** Creates a copy of `this`.
      *
      * @param target      the expression being forced
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      target: Expression                   = target,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Force = {
      if (
        target != this.target
        || location != this.location
        || passData != this.passData
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Force(target, location, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Force =
      copy(
        target = target.duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Force =
      copy(location = location)

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Force = {
      copy(target = fn(target))
    }

    /** String representation. */
    override def toString: String =
      s"""
         |Application.Force(
         |target = $target,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = List(target)

    /** @inheritdoc */
    override def showCode(indent: Int): String =
      s"(FORCE ${target.showCode(indent)})"
  }

  /** Literal applications in Enso. */
  sealed trait Literal extends Application {

    /** @inheritdoc */
    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Literal

    /** @inheritdoc */
    override def setLocation(location: Option[IdentifiedLocation]): Literal

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Literal
  }

  /** A representation of a typeset literal.
    *
    * These are necessary as they delimit pattern contexts.
    *
    * @param expression the expression of the typeset body
    * @param location the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Typeset(
    expression: Option[Expression],
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Literal
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Typeset =
      copy(expression = expression.map(fn.asScala))

    /** Creates a copy of `this`.
      *
      * @param expression  the expression of the typeset body
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updataed with the specified values
      */
    def copy(
      expression: Option[Expression]       = expression,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Typeset = {
      if (
        expression != this.expression
        || location != this.location
        || passData != this.passData
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Typeset(expression, location, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Typeset =
      copy(
        expression = expression.map(
          _.duplicate(
            keepLocations,
            keepMetadata,
            keepDiagnostics,
            keepIdentifiers
          )
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): Typeset = copy(location = location)

    /** String representation. */
    override def toString: String =
      s"""Application.Typeset(
         |expression = $expression,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] =
      expression.map(List(_)).getOrElse(List())

    /** @inheritdoc */
    override def showCode(indent: Int): String = {
      val exprString = if (expression.isDefined) {
        expression.get.showCode(indent)
      } else ""

      s"{ $exprString }"
    }
  }

  /** A representation of a vector literal.
    *
    * @param items the items being put in the vector
    * @param location the source location that the node corresponds to
    * @param passData the pass metadata associated with this node
    */
  sealed case class Sequence(
    items: List[Expression],
    override val location: Option[IdentifiedLocation],
    override val passData: MetadataStorage = new MetadataStorage()
  ) extends Literal
      with IRKind.Primitive
      with LazyDiagnosticStorage
      with LazyId {

    override def mapExpressions(
      fn: java.util.function.Function[Expression, Expression]
    ): Sequence =
      copy(items = items.map(fn.asScala))

    /** Creates a copy of `this`.
      *
      * @param items       the items held by this vector
      * @param location    the source location that the node corresponds to
      * @param passData    the pass metadata associated with this node
      * @param diagnostics compiler diagnostics for this node
      * @param id          the identifier for the new node
      * @return a copy of `this`, updated with the specified values
      */
    def copy(
      items: List[Expression]              = items,
      location: Option[IdentifiedLocation] = location,
      passData: MetadataStorage            = passData,
      diagnostics: DiagnosticStorage       = diagnostics,
      id: UUID @Identifier                 = id
    ): Sequence = {
      if (
        items != this.items
        || location != this.location
        || passData != this.passData
        || diagnostics != this.diagnostics
        || id != this.id
      ) {
        val res = Sequence(items, location, passData)
        res.diagnostics = diagnostics
        res.id          = id
        res
      } else this
    }

    /** @inheritdoc */
    override def duplicate(
      keepLocations: Boolean   = true,
      keepMetadata: Boolean    = true,
      keepDiagnostics: Boolean = true,
      keepIdentifiers: Boolean = false
    ): Sequence =
      copy(
        items = items.map(
          _.duplicate(
            keepLocations,
            keepMetadata,
            keepDiagnostics,
            keepIdentifiers
          )
        ),
        location = if (keepLocations) location else None,
        passData =
          if (keepMetadata) passData.duplicate else new MetadataStorage(),
        diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
        id          = if (keepIdentifiers) id else null
      )

    /** @inheritdoc */
    override def setLocation(
      location: Option[IdentifiedLocation]
    ): Sequence = copy(location = location)

    /** String representation. */
    override def toString: String =
      s"""
         |Application.Vector(
         |items = $items,
         |location = $location,
         |passData = ${this.showPassData},
         |diagnostics = $diagnostics,
         |id = $id
         |)
         |""".toSingleLine

    /** @inheritdoc */
    override def children: List[IR] = items

    /** @inheritdoc */
    override def showCode(indent: Int): String = {
      val itemsStr = items.map(_.showCode(indent)).mkString(", ")
      s"[$itemsStr]"
    }
  }

}
