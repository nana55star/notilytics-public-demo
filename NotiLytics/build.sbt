ThisBuild / scalaVersion := "2.13.16"
ThisBuild / javacOptions ++= Seq("--release", "17")

lazy val pekkoVersion = "1.0.3"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(
    name := "NotiLytics",
    version := "1.0.0",

    libraryDependencies ++= Seq(
      guice,
      ws,

      // --- Play + Pekko ---
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"      % pekkoVersion,

      // --- ✔ ADD THIS (needed for SearchStreamActorTest) ---
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,

      // --- Testing (JUnit 5 / Jupiter) ---
      "org.junit.jupiter" % "junit-jupiter-api"    % "5.10.2" % Test,
      "org.junit.jupiter" % "junit-jupiter-engine" % "5.10.2" % Test,
      "net.aichler"       % "jupiter-interface"    % "0.11.1" % Test,  // sbt <-> JUnit5 bridge
      "org.mockito"       % "mockito-core"         % "5.12.0" % Test,
      "org.assertj"       % "assertj-core"         % "3.25.1" % Test,
      "org.mockito"       % "mockito-inline"       % "5.2.0"  % Test
    ),

    // Tell sbt to use the JUnit 5 framework
    Test / testFrameworks += new TestFramework("net.aichler.junitplatform.JUnitPlatformFramework"),

    // Helpful for classpath isolation when running tests
    Test / fork := true,

    // --- JaCoCo: exclude Play-generated classes and templates from coverage ---
    Test / jacocoExcludes ++= Seq(
      "routes*",
      "router.*", "router.Routes*",
      "controllers.routes*",
      "controllers.Reverse*",
      "controllers.javascript.*",
      "views.html.*"
    ),

    // --- Javadoc configuration (include private methods) ---
    Compile / doc / javacOptions ++= Seq(
      "-private",          // include private fields/methods/classes
      "-Xdoclint:none",    // disable strict Javadoc validation
      "-notimestamp",      // remove build timestamps (clean diffs)
      "-linksource"        // include source code links
    )
  )
