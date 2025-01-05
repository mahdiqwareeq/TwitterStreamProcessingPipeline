import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonArray
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types._
import org.apache.spark.sql._

import scala.util.Try
import com.mongodb.spark._
import com.johnsnowlabs.nlp.DocumentAssembler
import com.johnsnowlabs.nlp.annotator._
import com.johnsnowlabs.nlp.util.io.ReadAs
import org.apache.spark.ml.Pipeline
import org.apache.spark.util.sketch.BloomFilter

object TweetsStreaming  {
  def main(args: Array[String]): Unit = {
    // Create Spark Session
    val spark = SparkSession.builder()
      .appName("ExternalProducerTwitterConsumer")
      .master("local[*]")
      .config("spark.mongodb.read.connection.uri", "mongodb://127.0.0.1/tweets_db.tweets")
      .config("spark.mongodb.write.connection.uri", "mongodb://127.0.0.1/tweets_db.tweets")
      .getOrCreate()

    import spark.implicits._

    // Kafka Consumer Configuration
    val kafkaDF = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .option("subscribe", "raw_tweets_topic")
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()

    // Parse kafka message and clean the received string
    val dataDF = kafkaDF
      .select($"value".cast("string").alias("json_col"))
      .withColumn("json_col", regexp_replace(col("json_col"), """\\\"""", "\""))
      .withColumn("json_col", regexp_replace(col("json_col"), "\"\\{", "{"))
      .withColumn("json_col", regexp_replace(col("json_col"), "}\"", "}"))
      .withColumn("json_col", regexp_replace(col("json_col"), """\\\\"""", """\\""""))

    // Split the received JSON string into an array of JSON strings
    val splitDF = dataDF.withColumn("json_array", split(col("json_col"), "\\\\n"))
    // Explode into individual rows
    val explodedDF = splitDF.withColumn("json_row", explode(col("json_array")))
    // Extract tweets data from the JSON
    val parsedDF = explodedDF.withColumn("parsed", from_json(col("json_row"), twitterSchema()))
      .select("parsed.*")
      .withColumn("created_at", regexp_replace(col("created_at"), "^[A-Za-z]+ ", ""))
      .withColumn("created_at", to_timestamp(col("created_at"), "MMM dd HH:mm:ss Z yyyy"))
      .withColumn("hashtags", col("entities.hashtags.text"))
      .withColumn("latitude", when($"geo".isNotNull, element_at(col("geo.coordinates"), 1)))
      .withColumn("longitude", when($"geo".isNotNull, element_at(col("geo.coordinates"), 2)))

    // Create Bloom Filter
    val expectedEntries = 100000L  // Expected number of unique tweets
    val falsePositiveRate = 0.001    // 0.1% FPR

    def createBloomFilter(): BloomFilter = {
      BloomFilter.create(expectedEntries, falsePositiveRate)
    }

    // Broadcast Bloom Filter Wrapper
    val bloomFilterWrapper = spark.sparkContext.broadcast {
      val bloomFilter = createBloomFilter()

      new Serializable {
        @transient var filter: BloomFilter = bloomFilter

        def mightContain(id: Long): Boolean = {
          if (filter == null) {
            filter = createBloomFilter()
          }
          filter.mightContain(id)
        }

        def put(id: Long): Unit = {
          if (filter == null) {
            filter = createBloomFilter()
          }
          filter.put(id)
        }
      }
    }

    // Deduplicate received records
    val processedDF = parsedDF.filter { row =>
      val tweetId = row.getAs[Long]("id")
        val isNewTweet = !bloomFilterWrapper.value.mightContain(tweetId)
        if (isNewTweet) {
          bloomFilterWrapper.value.put(tweetId)
        }
        isNewTweet
    }

    val documentAssembler = new DocumentAssembler()
      .setInputCol("text")
      .setOutputCol("document")

    val tokenizer = new Tokenizer()
      .setInputCols("document")
      .setOutputCol("token")

    val lemmatizer = new Lemmatizer()
      .setInputCols("token")
      .setOutputCol("lemma")
      .setDictionary("data/lemmas.txt", "->", "\t")

    val sentimentDetector = new SentimentDetector()
      .setInputCols("lemma", "document")
      .setOutputCol("sentimentScore")
      .setDictionary("data/default-sentiment-dict.txt", ",", ReadAs.TEXT)

    val pipeline = new Pipeline().setStages(Array(
      documentAssembler,
      tokenizer,
      lemmatizer,
      sentimentDetector,
    ))

    val finalDF = pipeline.fit(processedDF).transform(processedDF)
      .withColumn("sentimentScore", element_at(col("sentimentScore.result"), 1))
      .select(
        $"created_at".alias("created_at"),
        $"id".alias("tweet_id"),
        $"text".alias("text"),
        $"hashtags".alias("hashtags"),
        $"user.name".alias("user_name"),
        $"longitude".alias("longitude"),
        $"latitude".alias("latitude"),
        $"sentimentScore".alias("sentimentScore")
      )

    // Write stream to mongodb with detailed output
    val query = finalDF
      .writeStream
      .format("mongodb")
      .option("spark.mongodb.connection.uri", "mongodb://localhost:27017")
      .option("spark.mongodb.database", "tweets_db")
      .option("spark.mongodb.collection", "tweets")
      .option("checkpointLocation", "/tmp/kafka-checkpoints")
      .outputMode("append")
      .start()

    // Wait for termination
    spark.streams.awaitAnyTermination()
  }

  // Define the schema for Twitter JSON
  def twitterSchema(): StructType = {
    StructType(Seq(
      StructField("created_at", StringType, true),
      StructField("id", LongType, true),
      StructField("id_str", StringType, true),
      StructField("text", StringType, true),
      StructField("truncated", BooleanType, true),
      StructField("entities", StructType(Seq(
        StructField("hashtags", ArrayType(StructType(Seq(
          StructField("text", StringType, true),
          StructField("indices", ArrayType(IntegerType), true)
        ))), true),
        StructField("symbols", ArrayType(StringType), true),
        StructField("user_mentions", ArrayType(StringType), true),
        StructField("urls", ArrayType(StructType(Seq(
          StructField("url", StringType, true),
          StructField("expanded_url", StringType, true),
          StructField("display_url", StringType, true),
          StructField("indices", ArrayType(IntegerType), true)
        ))), true)
      )), true),
      StructField("source", StringType, true),
      StructField("in_reply_to_status_id", LongType, true),
      StructField("in_reply_to_status_id_str", StringType, true),
      StructField("in_reply_to_user_id", LongType, true),
      StructField("in_reply_to_user_id_str", StringType, true),
      StructField("in_reply_to_screen_name", StringType, true),
      StructField("user", StructType(Seq(
        StructField("id", LongType, true),
        StructField("id_str", StringType, true),
        StructField("name", StringType, true),
        StructField("screen_name", StringType, true),
        StructField("location", StringType, true),
        StructField("description", StringType, true),
        StructField("url", StringType, true),
        StructField("entities", StructType(Seq(
          StructField("url", StructType(Seq(
            StructField("urls", ArrayType(StructType(Seq(
              StructField("url", StringType, true),
              StructField("expanded_url", StringType, true),
              StructField("display_url", StringType, true),
              StructField("indices", ArrayType(IntegerType), true)
            ))), true)
          )), true),
          StructField("description", StructType(Seq(
            StructField("urls", ArrayType(StructType(Seq(
              StructField("url", StringType, true),
              StructField("expanded_url", StringType, true),
              StructField("display_url", StringType, true),
              StructField("indices", ArrayType(IntegerType), true)
            ))), true)
          )), true)
        )), true),
        StructField("protected", BooleanType, true),
        StructField("followers_count", IntegerType, true),
        StructField("friends_count", IntegerType, true),
        StructField("listed_count", IntegerType, true),
        StructField("created_at", StringType, true),
        StructField("favourites_count", IntegerType, true),
        StructField("utc_offset", StringType, true),
        StructField("time_zone", StringType, true),
        StructField("geo_enabled", BooleanType, true),
        StructField("verified", BooleanType, true),
        StructField("statuses_count", IntegerType, true),
        StructField("lang", StringType, true),
        StructField("contributors_enabled", BooleanType, true),
        StructField("is_translator", BooleanType, true),
        StructField("is_translation_enabled", BooleanType, true),
        StructField("profile_background_color", StringType, true),
        StructField("profile_background_image_url", StringType, true),
        StructField("profile_background_image_url_https", StringType, true),
        StructField("profile_background_tile", BooleanType, true),
        StructField("profile_image_url", StringType, true),
        StructField("profile_image_url_https", StringType, true),
        StructField("profile_banner_url", StringType, true),
        StructField("profile_link_color", StringType, true),
        StructField("profile_sidebar_border_color", StringType, true),
        StructField("profile_sidebar_fill_color", StringType, true),
        StructField("profile_text_color", StringType, true),
        StructField("profile_use_background_image", BooleanType, true),
        StructField("has_extended_profile", BooleanType, true),
        StructField("default_profile", BooleanType, true),
        StructField("default_profile_image", BooleanType, true),
        StructField("following", BooleanType, true),
        StructField("follow_request_sent", BooleanType, true),
        StructField("notifications", BooleanType, true),
        StructField("translator_type", StringType, true)
      )), true),
      StructField("geo", StructType(Seq(
        StructField("type", StringType, true),
        StructField("coordinates", ArrayType(DoubleType), true)
      )), true),
      StructField("coordinates", StructType(Seq(
        StructField("type", StringType, true),
        StructField("coordinates", ArrayType(DoubleType), true)
      )), true),
      StructField("place", StructType(Seq(
        StructField("id", StringType, true),
        StructField("url", StringType, true),
        StructField("place_type", StringType, true),
        StructField("name", StringType, true),
        StructField("full_name", StringType, true),
        StructField("country_code", StringType, true),
        StructField("country", StringType, true),
        StructField("contained_within", ArrayType(StringType), true),
        StructField("bounding_box", StructType(Seq(
          StructField("type", StringType, true),
          StructField("coordinates", ArrayType(ArrayType(ArrayType(DoubleType))), true)
        )), true),
        StructField("attributes", StructType(Seq()), true)
      )), true),
      StructField("contributors", StringType, true),
      StructField("is_quote_status", BooleanType, true),
      StructField("retweet_count", IntegerType, true),
      StructField("favorite_count", IntegerType, true),
      StructField("favorited", BooleanType, true),
      StructField("retweeted", BooleanType, true),
      StructField("possibly_sensitive", BooleanType, true),
      StructField("lang", StringType, true)
    ))
  }
}