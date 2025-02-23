package org.enso.compiler.core.ir
package expression
package errors

import org.enso.compiler.core.{IR, Identifier}

import java.util.UUID

/** A representation of an error resulting from wrong pattern matches.
  *
  * @param originalPattern pattern that resulted in the error
  * @param reason          the cause of this error
  * @param passData        the pass metadata associated with this node
  * @return a copy of `this`, updated with the specified values
  */
sealed case class Pattern(
  originalPattern: org.enso.compiler.core.ir.Pattern,
  reason: Pattern.Reason,
  passData: MetadataStorage = new MetadataStorage()
) extends Error
    with Diagnostic.Kind.Interactive
    with org.enso.compiler.core.ir.Pattern
    with LazyDiagnosticStorage
    with LazyId {

  /** Create a [[Pattern]] object.
    *
    * @param originalPattern pattern that resulted in the error
    * @param reason the cause of this error
    * @param passData the pass metadata associated with this node
    * @param diagnostics the compiler diagnostics
    */
  def this(
    originalPattern: org.enso.compiler.core.ir.Pattern,
    reason: Pattern.Reason,
    passData: MetadataStorage,
    diagnostics: DiagnosticStorage
  ) = {
    this(originalPattern, reason, passData)
    this.diagnostics = diagnostics
  }

  override def mapExpressions(
    fn: java.util.function.Function[Expression, Expression]
  ): Pattern =
    copy(originalPattern = originalPattern.mapExpressions(fn))

  override def setLocation(location: Option[IdentifiedLocation]): Pattern =
    copy(originalPattern = originalPattern.setLocation(location))

  /** Creates a copy of `this`.
    *
    * @param originalPattern the pattern that resulted in the error
    * @param reason          the cause of this error
    * @param passData        the pass metadata associated with this node
    * @param diagnostics     compiler diagnostics for this node
    * @param id              the identifier for the new node
    * @return a copy of `this`, updated with the specified values
    */
  def copy(
    originalPattern: org.enso.compiler.core.ir.Pattern = originalPattern,
    reason: Pattern.Reason                             = reason,
    passData: MetadataStorage                          = passData,
    diagnostics: DiagnosticStorage                     = diagnostics,
    id: UUID @Identifier                               = id
  ): Pattern = {
    if (
      originalPattern != this.originalPattern
      || reason != this.reason
      || passData != this.passData
      || diagnostics != this.diagnostics
      || id != this.id
    ) {
      val res = Pattern(originalPattern, reason, passData)
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
  ): Pattern =
    copy(
      originalPattern = originalPattern
        .duplicate(
          keepLocations,
          keepMetadata,
          keepDiagnostics,
          keepIdentifiers
        ),
      passData =
        if (keepMetadata) passData.duplicate else new MetadataStorage(),
      diagnostics = if (keepDiagnostics) diagnosticsCopy else null,
      id          = if (keepIdentifiers) id else null
    )

  override def message(source: IdentifiedLocation => String): String =
    reason.explain

  override def diagnosticKeys(): Array[Any] = Array(reason)

  override val location: Option[IdentifiedLocation] =
    originalPattern.location

  override def children: List[IR] = List(originalPattern)

  override def showCode(indent: Int): String =
    originalPattern.showCode(indent)
}

object Pattern {

  /** A representation of the reason the pattern is erroneous. */
  sealed trait Reason {

    /** Provides a human-readable explanation of the error.
      *
      * @return
      */
    def explain: String
  }

  /** A reason for pattern failing due to wrong arity.
    *
    * @param consName the constructor name.
    * @param expected expected field count.
    * @param actual   actual field count.
    */
  case class WrongArity(consName: String, expected: Int, actual: Int)
      extends Reason {
    override def explain: String =
      s"Wrong number of fields when matching on $consName." +
      s" Expected $expected fields, but provided $actual"
  }

  /** An error when a project-private constructor is used in the pattern.
    * @param consName Name of the constructor. Does not have to be fully qualified.
    * @param callerProject The project name of the caller.
    * @param calleeProject The project name of the callee. The constructor is in this project.
    */
  case class PrivateConstructor(
    consName: String,
    callerProject: String,
    calleeProject: String
  ) extends Reason {
    override def explain: String =
      s"Project-private constructor '$consName' in project '$calleeProject' cannot be used from project '$callerProject'"
  }
}
