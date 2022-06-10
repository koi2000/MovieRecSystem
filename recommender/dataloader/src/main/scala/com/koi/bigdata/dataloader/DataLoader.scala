package com.koi.bigdata.dataloader

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import java.net.InetAddress

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.transport.client.PreBuiltTransportClient

/**
 * @author koi
 * @date 2022/5/24 19:40
 */

/*
* Movie数据集，数据集通过^分割
* Rating数据集 用户对电影的评分
* Tag数据集 用户对电影的标签数据集
* */

/**
 * Movie 数据集
 *
 * 260                                         电影ID，mid
 * Star Wars: Episode IV - A New Hope (1977)   电影名称，name
 * Princess Leia is captured and held hostage  详情描述，descri
 * 121 minutes                                 时长，timelong
 * September 21, 2004                          发行时间，issue
 * 1977                                        拍摄时间，shoot
 * English                                     语言，language
 * Action|Adventure|Sci-Fi                     类型，genres
 * Mark Hamill|Harrison Ford|Carrie Fisher     演员表，actors
 * George Lucas                                导演，directors
 */

case class Movie(mid: Int, name: String, describe: String, timeLong: String, issue: String,
                 shoot: String, language: String, genres: String, actors: String, directors: String)

case class Rating(uid: Int, mid: Int, score: Double, timestamp: Int)

case class Tag(uid: Int, mid: Int, tag: String, timestamp: Int)

/**
 *
 * @param uri MongoDB 连接 url
 * @param db  MongoDB 数据库
 */
case class MongoConfig(uri: String, db: String)

/**
 *
 * @param httpHosts      http 主机列表，逗号分隔
 * @param transportHosts transport 主机列表
 * @param index          需要操作的索引
 * @param clustername    集群名称，默认 elasticsearch
 */
case class ESConfig(httpHosts: String, transportHosts: String, index: String, clustername: String)


object DataLoader {
  // 定义常量
  val MOVIE_DATA_PATH = "docs/data/movies.csv"
  val RATING_DATA_PATH = "docs/data/ratings.csv"
  val TAG_DATA_PATH = "docs/data/tags.csv"

  val ES_INDEX = "recommender"
  val ES_CLUSTER_NAME = "es-cluster"
  val MODULE_NAME = "DataLoader"

  val MONGODB_MOVIE_COLLECTION = "Movie"
  val MONGODB_RATING_COLLECTION = "Rating"
  val MONGODB_TAG_COLLECTION = "Tag"
  val ES_MOVIE_INDEX = "Movie"

  val TEST_URL = "192.168.87.152"

  val MONGO_URL = "mongodb://" + TEST_URL + ":27017/recommender"
  val MONGO_DB = "recommender"
  val ES_URL_HTTP = TEST_URL + ":9200"
  val ES_URL_TRANS = TEST_URL + ":9300"

  def main(args: Array[String]): Unit = {
    val config: Map[String, String] = Map(
      // 本地运行需要设置 "local[*]"
      "spark.cores" -> "local[*]",
      "mongo.uri" -> MONGO_URL,
      "mongo.db" -> MONGO_DB,
      "es.httpHosts" -> ES_URL_HTTP,
      "es.transportHosts" -> ES_URL_TRANS,
      "es.index" -> ES_INDEX,
      "es.cluster.name" -> ES_CLUSTER_NAME
    )

    // 需要创建一个SparkConf
    var sparkConf = new SparkConf().setAppName("DataLoader").setMaster(config.get("spark.cores").get)

    // 创建SparkSession
    val spark = SparkSession.builder()
      .config(sparkConf)
      .getOrCreate()

    // 引入SparkSession内部的隐式转换
    import spark.implicits._

    // 加载Movie，Rating，Tag加载进来
    var movieRDD = spark.sparkContext.textFile(MOVIE_DATA_PATH)
    // RDD 装换成 DataFrame
    val movieDF = movieRDD.map(
      item => {
        val attr = item.split("\\^")
        // (电影ID[mid],电影名称[name],详情描述[describe],时长[timelong],发行时间[issue],拍摄时间[shoot],语言[language],类型[genres],演员表[actors],导演[directors])
        Movie(attr(0).toInt, attr(1).trim, attr(2).trim, attr(3).trim, attr(4).trim,
          attr(5).trim, attr(6).trim, attr(7).trim, attr(8).trim, attr(9).trim)
      }
    ).toDF()

    // 读取评分
    var ratingRDD = spark.sparkContext.textFile(RATING_DATA_PATH)
    // 将评分RDD转换为DataFrame
    val ratingDF = ratingRDD.map(line => {
      val x = line.split(",")
      Rating(x(0).toInt, x(1).toInt, x(2).toDouble, x(3).toInt)
    }).toDF()

    var tagRDD = spark.sparkContext.textFile(TAG_DATA_PATH)
    // 将标签RDD转换为DataFrame
    val tagDF = tagRDD.map(line => {
      val x = line.split(",")
      Tag(x(0).toInt, x(1).toInt, x(2).toString, x(3).toInt)
    }).toDF()

    implicit val mongoConfig = MongoConfig(config("mongo.uri"), config("mongo.db"))

    // 将数据保存到 MongoDB
    // 所有的原始 DataFrame 的数据保存在 MongoDB 中
    storeDataIntoMongoDB(movieDF, ratingDF, tagDF)

    // 数据预处理，把 movie 对应的 tag 信息添加进去，加一列 tag1|tag2|tag3...
    import org.apache.spark.sql.functions._

    /**
     * mid, tags
     * tags: tag1|tag2|tag3...
     * 将所有的tag合并成一个字符串
     */
    val newTag = tagDF.groupBy($"mid")
      .agg(concat_ws("|", collect_set($"tag")).as("tags"))
      .select("mid", "tags")

    // newTag 和 movie 做 join，数据合并在一起，左外连接
    // 将movie的信息和新的tag合并在一起
    val movieWithTagsDF = movieDF.join(newTag, Seq("mid"), "left")

    implicit val esConfig = ESConfig(config("es.httpHosts"), config("es.transportHosts"), config("es.index"), config("es.cluster.name"))
    // 保存数据到 ES
    // 添加了电影标签的数据保存到 ES 中
    storeDataInES(movieWithTagsDF)

    spark.stop()
  }

  def storeDataIntoMongoDB(movieDF: DataFrame, ratingDF: DataFrame, tagDF: DataFrame)(implicit mongoConfig: MongoConfig): Unit = {
    // 新建一个 mongodb 的连接
    val mongoClient = MongoClient(MongoClientURI(mongoConfig.uri))

    // 如果 mongodb 中已经有相应的数据库，先删除
    mongoClient(mongoConfig.db)(MONGODB_MOVIE_COLLECTION).dropCollection()
    mongoClient(mongoConfig.db)(MONGODB_RATING_COLLECTION).dropCollection()
    mongoClient(mongoConfig.db)(MONGODB_TAG_COLLECTION).dropCollection()

    // 将DF数据写入对应的mongodb表中
    movieDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_MOVIE_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    ratingDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_RATING_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    tagDF.write
      .option("uri", mongoConfig.uri)
      .option("collection", MONGODB_TAG_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    // 对数据表建索引
    mongoClient(mongoConfig.db)(MONGODB_MOVIE_COLLECTION).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_RATING_COLLECTION).createIndex(MongoDBObject("uid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_RATING_COLLECTION).createIndex(MongoDBObject("mid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_TAG_COLLECTION).createIndex(MongoDBObject("uid" -> 1))
    mongoClient(mongoConfig.db)(MONGODB_TAG_COLLECTION).createIndex(MongoDBObject("mid" -> 1))

    mongoClient.close()
  }

  def storeDataInES(movieDF: DataFrame)(implicit eSConfig: ESConfig): Unit = {
    // 新建es配置
    val settings: Settings = Settings.builder()
      .put("cluster.name", eSConfig.clustername)
      .build()

    // 新建一个es客户端
    val esClient = new PreBuiltTransportClient(settings)

    val REGEX_HOST_PORT = "(.+):(\\d+)".r
    eSConfig.transportHosts.split(",").foreach {
      case REGEX_HOST_PORT(host: String, port: String) => {
        println("使用的 es 地址是：", host, port)
        esClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port.toInt))
      }
    }

    // 先清理遗留的数据
    if (esClient.admin().indices().exists(new IndicesExistsRequest(eSConfig.index))
      .actionGet()
      .isExists
    ) {
      esClient.admin().indices().delete(new DeleteIndexRequest(eSConfig.index))
    }

    esClient.admin().indices().create(new CreateIndexRequest(eSConfig.index))

    movieDF.write
      .option("es.nodes", eSConfig.httpHosts)
      .option("es.http.timeout", "100m")
      .option("es.mapping.id", "mid")
      .mode("overwrite")
      .format("org.elasticsearch.spark.sql")
      .save(eSConfig.index + "/" + ES_MOVIE_INDEX)
  }
}
