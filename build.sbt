import scala.collection.Seq

ThisBuild / version := "1.0.0"

Compile / unmanagedBase := baseDirectory.value / "lib"
lazy val tapirVersion = "1.13.3"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked"
  // "-Ystatistics",
)
addCommandAlias("openapi", "runMain org.ibm.WriteOpenApi")

lazy val commonSettings = Seq(
  scalaVersion    := "3.7.1",
  assembly / test := {} // disables tests during assembly
)

lazy val server = project
  .in(file("."))
  .enablePlugins(SbtTwirl)
  .settings(
    name                 := "wxo-embedded-chat",
    scalaVersion         := "3.7.1",
    Compile / mainClass  := Some("org.ibm.App"),
    assembly / mainClass := (Compile / mainClass).value,
    scalacOptions ++= Seq(
      "-Xlog-implicits", // see implicit resolution attempts
      // "-Xprint:typer",     // show expanded code after typer phase
      "-Ystatistics", // show phase timings to spot hotspots
      "-Xmax-inlines:532"
    ),
    libraryDependencies ++= Seq(
      "org.xerial"                             % "sqlite-jdbc"             % "3.46.1.0",
      "org.bouncycastle"                       % "bcprov-jdk18on"          % "1.77",
      "com.github.jwt-scala"                  %% "jwt-core"                % "9.4.5",
      "com.softwaremill.sttp.client4"         %% "core"                    % "4.0.13",
      "org.jsoup"                              % "jsoup"                   % "1.21.2",
      "com.softwaremill.sttp.client4"         %% "core"                    % "4.0.13",
      "org.typelevel"                         %% "cats-effect"             % "3.7-4972921",
      "org.http4s"                            %% "http4s-blaze-server"     % "0.23.17",
      "org.http4s"                            %% "http4s-blaze-client"     % "0.23.17",
      "org.http4s"                            %% "http4s-dsl"              % "0.23.33",
      "org.http4s"                            %% "http4s-core"             % "0.23.33",
      "com.softwaremill.sttp.tapir"           %% "tapir-jsoniter-scala"    % tapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-http4s-server"     % tapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-swagger-ui-bundle" % tapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-openapi-docs"      % tapirVersion,
      "org.typelevel"                         %% "munit-cats-effect"       % "2.1.0"   % Test,
      "org.http4s"                            %% "http4s-client"           % "0.23.33" % Test,
      "org.http4s"                            %% "http4s-ember-client"     % "0.23.33" % Test,
      "com.ibm.db2"                            % "jcc"                     % "12.1.2.0",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"     % "2.28.2",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros"   % "2.28.2"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    dependencyOverrides += "org.scala-lang" %% "scala3-library" % "3.7.1",
    Compile / unmanagedJars := {
      val repo = (ThisBuild / baseDirectory).value / "lib"
      val jars = (repo ** "*.jar").get
        .filterNot(_.getName.toLowerCase.startsWith("caseclassgenerator"))

      Attributed.blankSeq(jars)
    },
    assembly / assemblyMergeStrategy := {

      case PathList(
            "META-INF",
            "maven",
            "org.webjars",
            "swagger-ui",
            "pom.properties"
          ) =>
        MergeStrategy.singleOrError
      case PathList("META-INF", "resources", "webjars", "swagger-ui", _*) =>
        MergeStrategy.singleOrError
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat

      case PathList("META-INF", _*) => MergeStrategy.discard
      // Other cases
      case PathList("META-INF", "versions", "9", "module-info.class") =>
        MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.exists(_.endsWith("-NOTICE")) =>
        MergeStrategy.concat
      case PathList("META-INF", xs @ _*)                => MergeStrategy.discard
      case PathList(ps @ _*) if ps.last == "schema.sql" => MergeStrategy.first
      case PathList(ps @ _*) if ps.last == "module-info.class" =>
        MergeStrategy.discard
      case x => MergeStrategy.defaultMergeStrategy(x)
    }
  )
  .settings(commonSettings)
