//addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.17")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.3")
addSbtPlugin("com.eed3si9n"     % "sbt-assembly"        % "0.14.6")
///resolvers += "Local Maven Repo" at "file://" + new java.io.File("lib").getAbsolutePath
addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "2.0.9")

//addSbtPlugin("org.pwharned" % "caseclassgenerator" % "0.1.0-SNAPSHOT")
