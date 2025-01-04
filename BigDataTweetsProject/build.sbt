ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.18"

ThisBuild / libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.5.3",
  "org.mongodb.spark" %% "mongo-spark-connector" % "10.4.0",
  "org.apache.spark" %% "spark-core" % "3.5.3",
  "org.apache.spark" %% "spark-sql" % "3.5.3",
  "org.apache.spark" %% "spark-mllib" % "3.5.3",
  "org.apache.spark" %% "spark-streaming" % "3.5.3",
  "com.johnsnowlabs.nlp" %% "spark-nlp-silicon" % "5.5.1"
)

// Spark NLP resolver
resolvers += "Spark Packages Repo" at "https://repos.spark-packages.org/"

lazy val root = (project in file("."))
  .settings(
    name := "BigDataTweetsProject"
  )
