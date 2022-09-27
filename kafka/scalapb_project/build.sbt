scalaVersion := "2.13.6"

fork := true
javaOptions ++= Seq(
	"-Xmx4g"
)

Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)
PB.protocVersion := "3.17.3"

// (optional) If you need scalapb/scalapb.proto or anything from
// google/protobuf/*.proto
libraryDependencies ++= Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    "com.thesamet.scalapb" %% "scalapb-json4s" % "0.11.1",
    "com.typesafe.akka" %% "akka-stream" % "2.6.15",
    "com.typesafe.akka" %% "akka-stream-kafka" % "2.1.1",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.google.guava" % "guava" % "30.1.1-jre"
)

assembly / assemblyMergeStrategy := {
  case "version.conf" => MergeStrategy.concat
  // See https://github.com/akka/akka/issues/29456
  case PathList("google", "protobuf", file) if file.split('.').last == "proto" => MergeStrategy.first
  case PathList("google", "protobuf", "compiler", "plugin.proto") => MergeStrategy.first
  case other => MergeStrategy.defaultMergeStrategy(other)
}
