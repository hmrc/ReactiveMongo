import sbt._
import sbt.Keys._
import uk.gov.hmrc.SbtAutoBuildPlugin
import uk.gov.hmrc.versioning.SbtGitVersioning
import uk.gov.hmrc.gitstamp.GitStamp._
import scala.language.postfixOps

object BuildSettings {
  val buildVersion = "0.13.0"

  val filter = { (ms: Seq[(File, String)]) =>
    ms filter {
      case (file, path) =>
        path != "logback.xml" && !path.startsWith("toignore") && !path.startsWith("samples")
    }
  }

  val buildSettings = Defaults.coreDefaultSettings ++ Seq(
    organization := "uk.gov.hmrc",
    version := buildVersion,
    scalaVersion := "2.11.6",
    crossScalaVersions  := Seq("2.11.6", "2.10.4"),
    crossVersion := CrossVersion.binary,
    javaOptions in test ++= Seq("-Xmx512m", "-XX:MaxPermSize=512m"),
    //fork in Test := true, // Don't share executioncontext between SBT CLI/tests
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-target:jvm-1.6"),
    scalacOptions in (Compile, doc) ++= Seq("-unchecked", "-deprecation", "-diagrams", "-implicits", "-skip-packages", "samples"),
    scalacOptions in (Compile, doc) ++= Opts.doc.title("ReactiveMongo API"),
    scalacOptions in (Compile, doc) ++= Opts.doc.version(buildVersion),
    shellPrompt := ShellPrompt.buildShellPrompt,
    mappings in (Compile, packageBin) ~= filter,
    mappings in (Compile, packageSrc) ~= filter,
    mappings in (Compile, packageDoc) ~= filter) ++
    Travis.settings ++ Format.settings
}

//object Publish {
//
//  def targetRepository: Def.Initialize[Option[Resolver]] = Def.setting {
//    val nexus = "https://oss.sonatype.org/"
//    val snapshotsR = "snapshots" at nexus + "content/repositories/snapshots"
//    val releasesR  = "releases"  at nexus + "service/local/staging/deploy/maven2"
//    val resolver = if (isSnapshot.value) snapshotsR else releasesR
//    Some(resolver)
//  }
//
//  lazy val settings = Seq(
//    publishMavenStyle := true,
//    publishTo := targetRepository.value,
//    publishArtifact in Test := false,
//    pomIncludeRepository := { _ => false },
//    licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
//    homepage := Some(url("http://reactivemongo.org")),
//    pomExtra := (
//      <scm>
//        <url>git://github.com/ReactiveMongo/ReactiveMongo.git</url>
//        <connection>scm:git://github.com/ReactiveMongo/ReactiveMongo.git</connection>
//      </scm>
//      <developers>
//        <developer>
//          <id>sgodbillon</id>
//          <name>Stephane Godbillon</name>
//          <url>http://stephane.godbillon.com</url>
//        </developer>
//      </developers>))
//}

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
  val netty = "io.netty" % "netty" % "3.9.4.Final" cross CrossVersion.Disabled

  val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.3.6"

  val playIteratees = "com.typesafe.play" %% "play-iteratees" % "2.5.12"

  val specs = "org.specs2" %% "specs2-core" % "2.4.9" % "test"

  val log4jVersion = "2.0.2"
  val log4j = Seq("org.apache.logging.log4j" % "log4j-api" % log4jVersion, "org.apache.logging.log4j" % "log4j-core" % log4jVersion)

  val shapelessTest = "com.chuusai" % "shapeless" % "2.0.0" %
    Test cross CrossVersion.binaryMapped {
    case "2.10" => "2.10.4"
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

  val projectPrefix = "ReactiveMongo"

  lazy val reactivemongo =
    Project(
      s"$projectPrefix-Root",
      file("."),
      settings = buildSettings ++ Seq(publishArtifact := false, autoSourceHeader := false) ).
      settings(UnidocPlugin.unidocSettings: _*).
      enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning).
      aggregate(driver, bson, bsonmacros)

  lazy val driver = Project(
    projectPrefix,
    file("driver"),
    settings = buildSettings ++ Seq(
      resolvers := resolversList,
      autoSourceHeader := false,
      libraryDependencies ++= Seq(
        netty,
        akkaActor,
        playIteratees,
        commonsCodec,
        shapelessTest,
        specs) ++ log4j,
      testOptions in Test += Tests.Cleanup(cl => {
        import scala.language.reflectiveCalls
        val c = cl.loadClass("Common$")
        type M = { def closeDriver(): Unit }
        val m: M = c.getField("MODULE$").get(null).asInstanceOf[M]
        m.closeDriver()
      }))).dependsOn(bsonmacros).enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)


  lazy val bson = Project(
    s"$projectPrefix-BSON",
    file("bson"),
    settings = buildSettings ++ Seq(autoSourceHeader := false)).
    settings(libraryDependencies += specs).enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)

  lazy val bsonmacros = Project(
    s"$projectPrefix-BSON-Macros",
    file("macros"),
    settings = buildSettings ++ Seq(
      SbtAutoBuildPlugin.autoSourceHeader := false,
      libraryDependencies +=
        "org.scala-lang" % "scala-compiler" % scalaVersion.value
    )).
    settings(libraryDependencies += specs).
    dependsOn(bson).enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)
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