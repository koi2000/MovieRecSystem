# MovieRecSystem
开启zookeeper

```shell
bin/zkServer.sh start
查看状态
bin/zkServer.sh status
```

在kafka中创建队列

```
bin/kafka-topics.sh --create --zookeeper linux:2181 --replication-factor 1 --partitions 1 --topic recommender
bin/kafka-topics.sh --create --zookeeper linux:2181 --replication-factor 1 --partitions 1 --topic log
```

调度离线服务的脚本

```
/home/koi/spark/bin/spark-submit --class com.koi.bigdata.offline.RecommenderTrainerApp /home/koi/offline/offline.jar
```

启动kafka处理日志

bin/kafka-server-start.sh -daemon ./config/server.properties

```
java -cp kafkastream-jar-with-dependencies.jar  com.koi.bigdata.kafkaStream.Application linux:9092 linux:2181 log  recommender
```

查看kafka消息

```
./bin/kafka-topics.sh --describe --zookeeper linux:2181 --topic log

生产者生产消息
./bin/kafka-console-producer.sh --broker-list linux:9092 --topic log
```



启动flume，开始监听日志

```
bin/flume-ng agent -c ./conf/ -f ./conf/log-kafka.properties -n agent -Dflume.root.logger=INFO,console
```

提交流式计算任务到spark

```
/home/koi/spark/bin/spark-submit --class com.koi.bigdata.StreamingRecommender /home/koi/streamRecommender/StreamingRecommender-jar-with-dependencies.jar
```
