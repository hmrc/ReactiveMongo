import sbt._
import sbt.Keys._
import sbtassembly.AssemblyKeys.assemblyMergeStrategy
import sbtassembly.{MergeStrategy, PathList}
import uk.gov.hmrc.SbtAutoBuildPlugin
import uk.gov.hmrc.versioning.SbtGitVersioning

import scala.language.postfixOps

object BuildSettings {
  val buildVersion = "0.15.1"

  val filter = { (ms: Seq[(File, String)]) =>
    ms filter {
      case (file, path) =>
        path != "logback.xml" && !path.startsWith("toignore") && !path.startsWith("samples")
    }
  }

  import sbtassembly.AssemblyKeys.assembly

  lazy val mStrat = assemblyMergeStrategy in assembly := {
    case PathList("META-INF", xs@_*) => MergeStrategy.discard
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy (x)
  }

  val buildSettings = Defaults.coreDefaultSettings ++ Seq(
    organization := "uk.gov.hmrc",
    version := buildVersion,
    scalaVersion := "2.11.7",
    javaOptions in test ++= Seq("-Xmx512m", "-XX:MaxPermSize=512m"),
    //fork in Test := true, // Don't share executioncontext between SBT CLI/tests
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-target:jvm-1.6"),
    scalacOptions in (Compile, doc) ++= Seq("-unchecked", "-deprecation", "-diagrams", "-implicits", "-skip-packages", "samples"),
    scalacOptions in (Compile, doc) ++= Opts.doc.title("ReactiveMongo API"),
    scalacOptions in (Compile, doc) ++= Opts.doc.version(buildVersion),
    shellPrompt := ShellPrompt.buildShellPrompt,
//    mStrat,
    mappings in (Compile, packageBin) ~= filter,
    mappings in (Compile, packageSrc) ~= filter,
    mappings in (Compile, packageDoc) ~= filter) ++
    Travis.settings ++ Format.settings
}


object Format {
  import com.typesafe.sbt.SbtScalariform._

  lazy val settings = scalariformSettings ++ Seq(
    ScalariformKeys.preferences := formattingPreferences)

  lazy val formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences().
      setPreference(AlignParameters, true).
      setPreference(AlignSingleLineCaseStatements, true).
      setPreference(CompactControlReadability, true).
      setPreference(CompactStringConcatenation, false).
      setPreference(DoubleIndentClassDeclaration, true).
      setPreference(FormatXml, true).
      setPreference(IndentLocalDefs, false).
      setPreference(IndentPackageBlocks, true).
      setPreference(IndentSpaces, 2).
      setPreference(MultilineScaladocCommentsStartOnFirstLine, false).
      setPreference(PreserveSpaceBeforeArguments, false).
      setPreference(PreserveDanglingCloseParenthesis, false).
      setPreference(RewriteArrowSymbols, false).
      setPreference(SpaceBeforeColon, false).
      setPreference(SpaceInsideBrackets, false).
      setPreference(SpacesWithinPatternBinders, true)
  }
}

// Shell prompt which show the current project,
// git branch and build version
object ShellPrompt {
  object devnull extends ProcessLogger {
    def info(s: => String) {}

    def error(s: => String) {}

    def buffer[T](f: => T): T = f
  }

  def currBranch = (
    ("git status -sb" lines_! devnull headOption)
      getOrElse "-" stripPrefix "## ")

  val buildShellPrompt = {
    (state: State) =>
    {
      val currProject = Project.extract(state).currentProject.id
      "%s:%s> ".format(
        currProject, currBranch)
    }
  }
}

object Resolvers {
  val typesafe = Seq(
    "Typesafe repository snapshots" at "http://repo.typesafe.com/typesafe/snapshots/",
    "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/")
  val resolversList = typesafe
}

object Dependencies {

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.3.16"
  val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % "2.3.16" % "test"

  val playIteratees = "com.typesafe.play" %% "play-iteratees" % "2.5.12"

  val specs = "org.specs2" %% "specs2-core" % "3.8.6" % "test"

  val findbugs = "com.google.code.findbugs" % "jsr305" % "1.3.9"

  val shapelessTest = "com.chuusai" % "shapeless" % "2.3.2" %
    Test cross CrossVersion.binaryMapped {
    case "2.10" => "2.10.5"
    case x => x
  }

  val commonsCodec = "commons-codec" % "commons-codec" % "1.10"
}

object ReactiveMongoBuild extends Build {
  import BuildSettings._
  import Resolvers._
  import Dependencies._
  import sbtunidoc.{ Plugin => UnidocPlugin }
  import SbtAutoBuildPlugin.autoSourceHeader

  import scala.xml.{ Elem => XmlElem, Node => XmlNode }
  private def transformPomDependencies(tx: XmlElem => Option[XmlNode]): XmlNode => XmlNode = { node: XmlNode =>
    import scala.xml.{ NodeSeq, XML }
    import scala.xml.transform.{ RewriteRule, RuleTransformer }

    val tr = new RuleTransformer(new RewriteRule {
      override def transform(node: XmlNode): NodeSeq = node match {
        case e: XmlElem if e.label == "dependency" => tx(e) match {
          case Some(n) => n
          case _ => NodeSeq.Empty
        }

        case _ => node
      }
    })

    tr.transform(node).headOption match {
      case Some(transformed) => transformed
      case _ => sys.error("Fails to transform the POM")
    }
  }

  val projectPrefix = "ReactiveMongo"

  import sbtassembly.{
  AssemblyKeys, PathList, ShadeRule
  }, AssemblyKeys._

  lazy val mStrat = assemblyMergeStrategy in assembly := {
    case PathList("META-INF", xs@_*) => MergeStrategy.discard
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy (x)
  }

  lazy val reactivemongo =
    Project(
      s"$projectPrefix-Root",
      file("."),
      settings = buildSettings ++ Seq(publishArtifact := false, autoSourceHeader := false) ).
      settings(UnidocPlugin.unidocSettings: _*).
      enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning).
      aggregate(bson, bsonmacros, shaded, driver, jmx)

  val driverCleanup = taskKey[Unit]("Driver compilation cleanup")


  val slf4jVer = "1.7.12"
  val log4jVer = "2.5"

  val slf4j = "org.slf4j" % "slf4j-api" % slf4jVer
  val slf4jSimple = "org.slf4j" % "slf4j-simple" % slf4jVer

  val logApi = Seq(
    slf4j % "provided",
    "org.apache.logging.log4j" % "log4j-api" % log4jVer // deprecated
  ) ++ Seq("log4j-core", "log4j-slf4j-impl").map(
    "org.apache.logging.log4j" % _ % log4jVer % Test)

  private val driverFilter: Seq[(File, String)] => Seq[(File, String)] = {
    (_: Seq[(File, String)]).filter {
      case (file, name) =>
        !(name endsWith "external/reactivemongo/StaticListenerBinder.class")
    }
  } andThen BuildSettings.filter


  lazy val shaded = Project(
    s"$projectPrefix-Shaded",
    file("shaded"),
    settings = buildSettings ++ Seq(
      crossPaths := false,
      autoScalaLibrary := false,
      libraryDependencies ++= Seq(
        "io.netty" % "netty" % "3.10.6.Final",
        "com.google.guava" % "guava" % "19.0"
      ),
      assemblyShadeRules in assembly := Seq(
        ShadeRule.rename("org.jboss.netty.**" -> "shaded.netty.@1").inAll,
        ShadeRule.rename("com.google.**" -> "shaded.google.@1").inAll
      ),
      packageBin in Compile := target.value / (
        assemblyJarName in assembly).value
    )
  )

  lazy val driver = Project(
    projectPrefix,
    file("driver"),
    settings = buildSettings ++ Seq(
      resolvers := resolversList,
      autoSourceHeader := false,
      compile in Compile <<= (compile in Compile).dependsOn(assembly in shaded),

      driverCleanup := {
        val classDir = (classDirectory in Compile).value
        val extDir = {
          val d = target.value / "external" / "reactivemongo"
          d.mkdirs(); d
        }

        val classFile = "StaticListenerBinder.class"
        val listenerClass = classDir / "external" / "reactivemongo" / classFile

        streams.value.log(s"Cleanup $listenerClass ...")

        IO.move(listenerClass, extDir / classFile)
      },
      driverCleanup <<= driverCleanup.triggeredBy(compile in Compile),
//      pomPostProcess := transformPomDependencies { _ => None },
//      makePom <<= makePom.dependsOn(assembly),
      unmanagedJars in Compile := {
        val shadedDir = (target in shaded).value
        val shadedJar = (assemblyJarName in (shaded, assembly)).value

        (shadedDir / "classes").mkdirs() // Findbugs workaround

        Seq(Attributed(shadedDir / shadedJar)(AttributeMap.empty))
      },
      libraryDependencies ++= Seq(
        playIteratees, commonsCodec, shapelessTest, specs, akkaActor, akkaTestkit, findbugs) ++ logApi,
      testOptions in Test += Tests.Cleanup(commonCleanup),
      mappings in (Compile, packageBin) ~= driverFilter,
      //mappings in (Compile, packageDoc) ~= driverFilter,
      mappings in (Compile, packageSrc) ~= driverFilter
    )
  ).dependsOn(bsonmacros, shaded).enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)

  lazy val bson = Project(
    s"$projectPrefix-BSON",
    file("bson"),
    settings = buildSettings ++ Seq(autoSourceHeader := false)).
    settings(libraryDependencies ++= Seq(
      specs,
      "org.typelevel" %% "discipline" % "0.7.2" % Test,
      "org.specs2" %% "specs2-scalacheck" % "3.8.6" % Test,
      "org.spire-math" %% "spire-laws" % "0.13.0" % Test
    )).
    enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)

  lazy val bsonmacros = Project(
    s"$projectPrefix-BSON-Macros",
    file("macros"),
    settings = buildSettings ++ Seq(
      SbtAutoBuildPlugin.autoSourceHeader := false,
      libraryDependencies +=
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
    )).
    settings(libraryDependencies += specs).
    dependsOn(bson).enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)

  lazy val jmx = Project(
    s"$projectPrefix-JMX",
    file("jmx"), settings = buildSettings).
    settings(
      pomPostProcess := providedInternalDeps,
      testOptions in Test += Tests.Cleanup(commonCleanup),
      libraryDependencies ++= Seq(specs) ++ logApi).dependsOn(driver)

  private val providedInternalDeps: XmlNode => XmlNode = {
    import scala.xml.NodeSeq
    import scala.xml.transform.{ RewriteRule, RuleTransformer }

    val asProvided = new RuleTransformer(new RewriteRule {
      override def transform(node: XmlNode): NodeSeq = node match {
        case e: XmlElem if e.label == "scope" =>
          NodeSeq.Empty

        case _ => node
      }
    })

    transformPomDependencies { dep: scala.xml.Elem =>
      if ((dep \ "groupId").text == "org.reactivemongo") {
        asProvided.transform(dep).headOption.collectFirst {
          case e: XmlElem => e.copy(
            child = e.child :+ <scope>provided</scope>)
        }
      } else Some(dep)
    }
  }

  private val commonCleanup: ClassLoader => Unit = { cl =>
    import scala.language.reflectiveCalls

    val c = cl.loadClass("Common$")
    type M = { def close(): Unit }
    val m: M = c.getField("MODULE$").get(null).asInstanceOf[M]

    m.close()
  }

}

object Travis {
  val travisSnapshotBranches =
    SettingKey[Seq[String]]("branches that can be published on sonatype")

  val travisCommand = Command.command("publishSnapshotsFromTravis") { state =>
    val extracted = Project extract state
    import extracted._
    import scala.util.Properties.isJavaAtLeast

    val thisRef = extracted.get(thisProjectRef)

    val isSnapshot = getOpt(version).exists(_.endsWith("SNAPSHOT"))
    val isTravisEnabled = sys.env.get("TRAVIS").exists(_ == "true")
    val isNotPR = sys.env.get("TRAVIS_PULL_REQUEST").exists(_ == "false")
    val isBranchAcceptable = sys.env.get("TRAVIS_BRANCH").exists(branch => getOpt(travisSnapshotBranches).exists(_.contains(branch)))
    val isJavaVersion = !isJavaAtLeast("1.7")

    if (isSnapshot && isTravisEnabled && isNotPR && isBranchAcceptable) {
      println(s"not publishing in HMRC bulid.")

    } else {
      println(s"not publishing $thisRef to Sonatype: isSnapshot=$isSnapshot, isTravisEnabled=$isTravisEnabled, isNotPR=$isNotPR, isBranchAcceptable=$isBranchAcceptable, javaVersionLessThen_1_7=$isJavaVersion")
    }

    state
  }

  val settings = Seq(
    Travis.travisSnapshotBranches := Seq("master"),
    commands += Travis.travisCommand)

}
