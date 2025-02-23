package org.enso.cli

import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, DecodingFailure}
import org.enso.scala.yaml.YamlDecoder
import org.yaml.snakeyaml.nodes.{Node, ScalarNode}
import org.yaml.snakeyaml.error.YAMLException

/** Represents one of the supported platforms (operating systems).
  */
sealed trait OS {

  /** Name of this operating system as included in the configuration.
    */
  def configName: String

  /** Checks if the provided `os.name` matches this operating system.
    */
  def matches(osName: String): Boolean = osName.toLowerCase.contains(configName)
}

/** Gathers helper functions useful for dealing with platform-specific
  * behaviour.
  */
object OS {

  private val logger = Logger[OS.type]

  /** Represents the Linux operating system.
    */
  case object Linux extends OS {

    /** @inheritdoc
      */
    val configName: String = "linux"
  }

  /** Represents the macOS operating system.
    */
  case object MacOS extends OS {

    /** @inheritdoc
      */
    val configName: String = "macos"

    /** @inheritdoc
      */
    override def matches(osName: String): Boolean =
      osName.toLowerCase.contains("mac")
  }

  /** Represents the Windows operating system.
    */
  case object Windows extends OS {

    /** @inheritdoc
      */
    val configName: String = "windows"
  }

  /** Checks if the application is being run on Windows.
    */
  def isWindows: Boolean =
    operatingSystem == OS.Windows

  def isUNIX: Boolean =
    operatingSystem == OS.Linux || operatingSystem == OS.MacOS

  /** Returns which [[OS]] this program is running on.
    */
  lazy val operatingSystem: OS = detectOS

  private val ENSO_OPERATING_SYSTEM = "ENSO_OPERATING_SYSTEM"

  private val knownOS = Seq(Linux, MacOS, Windows)
  private lazy val knownOSPossibleValuesString =
    knownOS.map(os => s"`${os.configName}`").mkString(", ")

  private def detectOS: OS = {
    val overridenName = Option(System.getenv(ENSO_OPERATING_SYSTEM))
    overridenName match {
      case Some(value) =>
        knownOS.find(value.toLowerCase == _.configName) match {
          case Some(overriden) =>
            logger.debug(
              "OS overriden by [{}] to [{}].",
              ENSO_OPERATING_SYSTEM,
              overriden
            )
            return overriden
          case None =>
            logger.warn(
              "{} is set to an unknown value [{}], " +
              "ignoring. Possible values are [{}].",
              ENSO_OPERATING_SYSTEM,
              value,
              knownOSPossibleValuesString
            )
        }
      case None =>
    }

    val name       = System.getProperty("os.name")
    val possibleOS = knownOS.filter(_.matches(name))
    if (possibleOS.length == 1) {
      possibleOS.head
    } else {
      logger.error(
        "Could not determine a supported operating system. Please make sure " +
        "the OS you are running is supported. You can try to manually " +
        "override the operating system detection by setting an environment " +
        "variable [{}] to one of the possible values " +
        "[{}] depending on the system that your OS most behaves like.",
        ENSO_OPERATING_SYSTEM,
        knownOSPossibleValuesString
      )
      throw new IllegalStateException(
        "fatal: Could not detect the operating system."
      )
    }
  }

  /** Name of the architecture that the program is running on.
    */
  val architecture: String =
    if (System.getProperty("os.arch").contains("aarch64")) "aarch64"
    else "amd64"

  /** Wraps the base executable name with an optional platform-dependent
    * extension.
    */
  def executableName(baseName: String): String =
    if (isWindows) baseName + ".exe" else baseName

  /** A [[Decoder]] instance allowing to parse the OS name from JSON and YAML
    * configuration.
    */
  implicit val decoder: Decoder[OS] = { json =>
    json.as[String].flatMap { string =>
      knownOS.find(_.configName == string).toRight {
        DecodingFailure(
          s"`$string` is not a valid OS name. " +
          s"Possible values are $knownOSPossibleValuesString.",
          json.history
        )
      }
    }
  }

  implicit val yamlDecoder: YamlDecoder[OS] = (node: Node) => {
    node match {
      case s: ScalarNode =>
        s.getValue match {
          case Linux.configName   => Right(Linux)
          case Windows.configName => Right(Windows)
          case MacOS.configName   => Right(MacOS)
          case os                 => Left(new YAMLException(s"Unsupported os `$os`"))
        }
      case _ =>
        Left(new YAMLException("Expected a plain string value"))
    }
  }
}
