package com.koi.bigdata.business.service;

import com.koi.bigdata.business.model.recom.Recommendation;
import com.koi.bigdata.business.model.request.*;
import com.koi.bigdata.business.utils.Constant;
import com.mongodb.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import org.bson.Document;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.index.query.FuzzyQueryBuilder;
import org.elasticsearch.index.query.MoreLikeThisQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class RecommenderService {

    // 混合推荐中 CF 的比例
    private static Double CF_RATING_FACTOR = 0.3;
    private static Double CB_RATING_FACTOR = 0.3;
    private static Double SR_RATING_FACTOR = 0.4;

    @Autowired
    private MongoClient mongoClient;
    @Autowired
    private TransportClient esClient;

    @Autowired
    private Jedis jedis;

    // 基于物品的协同过滤，得到相似度矩阵
    private List<Recommendation> findMovieCFRecs(int mid, int maxItems) {
        MongoCollection<Document> movieRecsCollection = mongoClient.getDatabase(Constant.MONGODB_DATABASE).getCollection(Constant.MONGODB_MOVIE_RECS_COLLECTION);
        Document movieRecs = movieRecsCollection.find(new Document("mid", mid)).first();
        return parseRecs(movieRecs, maxItems);
    }

    // 协同过滤推荐
    private List<Recommendation> findUserCFRecs(int uid, int maxItems) {
        MongoCollection<Document> movieRecsCollection = mongoClient.getDatabase(Constant.MONGODB_DATABASE).getCollection(Constant.MONGODB_USER_RECS_COLLECTION);
        Document userRecs = movieRecsCollection.find(new Document("uid", uid)).first();
        return parseRecs(userRecs, maxItems);
    }


    // 从els中查出物品的信息
    private List<Recommendation> findContentBasedMoreLikeThisRecommendations(int mid, int maxItems) {
        MoreLikeThisQueryBuilder query = QueryBuilders.moreLikeThisQuery(/*new String[]{"name", "describe", "genres", "actors", "directors", "tags"},*/
                new MoreLikeThisQueryBuilder.Item[]{new MoreLikeThisQueryBuilder.Item(Constant.ES_INDEX, Constant.ES_MOVIE_TYPE, String.valueOf(mid))});

        return parseESResponse(esClient.prepareSearch().setQuery(query).setSize(maxItems).execute().actionGet());
    }

    // 实时推荐
    private List<Recommendation> findStreamRecs(int uid, int maxItems) {
        MongoCollection<Document> streamRecsCollection = mongoClient.getDatabase(Constant.MONGODB_DATABASE).getCollection(Constant.MONGODB_STREAM_RECS_COLLECTION);
        Document streamRecs = streamRecsCollection.find(new Document("uid", uid)).first();
        return parseRecs(streamRecs, maxItems);
    }

    private List<Recommendation> parseRecs(Document document, int maxItems) {
        List<Recommendation> recommendations = new ArrayList<>();
        if (null == document || document.isEmpty())
            return recommendations;
        ArrayList<Document> recs = document.get("recs", ArrayList.class);
        for (Document recDoc : recs) {
            recommendations.add(new Recommendation(recDoc.getInteger("mid"), recDoc.getDouble("score")));
        }
        Collections.sort(recommendations, (o1, o2) -> o1.getScore() > o2.getScore() ? -1 : 1);
        return recommendations.subList(0, maxItems > recommendations.size() ? recommendations.size() : maxItems);
    }

    // 全文检索
    private List<Recommendation> findContentBasedSearchRecommendations(String text, int maxItems) {
        MultiMatchQueryBuilder query = QueryBuilders.multiMatchQuery(text, "name", "describe");
        return parseESResponse(esClient.prepareSearch().setIndices(Constant.ES_INDEX).setTypes(Constant.ES_MOVIE_TYPE).setQuery(query).setSize(maxItems).execute().actionGet());
    }

    private List<Recommendation> parseESResponse(SearchResponse response) {
        List<Recommendation> recommendations = new ArrayList<>();
        for (SearchHit hit : response.getHits()) {
            recommendations.add(new Recommendation((int) hit.getSourceAsMap().get("mid"), (double) hit.getScore()));
        }
        return recommendations;
    }

    // 混合推荐算法
    private List<Recommendation> findHybridRecommendations(int productId, int maxItems) {
        List<Recommendation> hybridRecommendations = new ArrayList<>();
        // 拿到物品相似度矩阵，放入分数
        //List<Recommendation> cfRecs = findMovieCFRecs(productId, maxItems);
        List<Recommendation> cfRecs = findUserCFRecs(productId, maxItems);
        for (Recommendation recommendation : cfRecs) {
            hybridRecommendations.add(new Recommendation(recommendation.getMid(), recommendation.getScore() * CF_RATING_FACTOR));
        }

        // 从els中找到各种描述与该电影最相似的物品，
//        if (jedis.exists("uid:" + rating.getUid()) && jedis.llen("uid:" + rating.getUid()) >= Constant.REDIS_MOVIE_RATING_QUEUE_SIZE) {
//            jedis.rpop("uid:" + rating.getUid());
//        }
        String key = "uid:" + productId;
        int mid = productId;
        if (jedis.exists(key)) {
            try {
                Long llen = jedis.llen(key);
                String val = jedis.lindex(key, llen - 1);
                String[] split = val.split(":");
                mid = Integer.parseInt(split[0]);
            }catch (Exception e){

            }
        }

        List<Recommendation> cbRecs = findContentBasedMoreLikeThisRecommendations(mid, maxItems);
        for (Recommendation recommendation : cbRecs) {
            hybridRecommendations.add(new Recommendation(recommendation.getMid(), recommendation.getScore() * CB_RATING_FACTOR));
        }

        // 从流式推荐中取出物品
        List<Recommendation> streamRecs = findStreamRecs(productId, maxItems);
        for (Recommendation recommendation : streamRecs) {
            hybridRecommendations.add(new Recommendation(recommendation.getMid(), recommendation.getScore() * SR_RATING_FACTOR));
        }

        Collections.sort(hybridRecommendations, (o1, o2) -> o1.getScore() > o2.getScore() ? -1 : 1);
        return hybridRecommendations.subList(0, maxItems > hybridRecommendations.size() ? hybridRecommendations.size() : maxItems);
    }


    public List<Recommendation> getCollaborativeFilteringRecommendations(MovieRecommendationRequest request) {
        return findMovieCFRecs(request.getMid(), request.getSum());
    }

    public List<Recommendation> getCollaborativeFilteringRecommendations(UserRecommendationRequest request) {

        return findUserCFRecs(request.getUid(), request.getSum());
    }

    public List<Recommendation> getContentBasedMoreLikeThisRecommendations(MovieRecommendationRequest request) {
        return findContentBasedMoreLikeThisRecommendations(request.getMid(), request.getSum());
    }

    public List<Recommendation> getContentBasedSearchRecommendations(SearchRecommendationRequest request) {
        return findContentBasedSearchRecommendations(request.getText(), request.getSum());
    }

    public List<Recommendation> getHybridRecommendations(MovieHybridRecommendationRequest request) {
        return findHybridRecommendations(request.getMid(), request.getSum());
    }


    public List<Recommendation> getHotRecommendations(HotRecommendationRequest request) {
        // 获取热门电影的条目
        MongoCollection<Document> rateMoreMoviesRecentlyCollection = mongoClient.getDatabase(Constant.MONGODB_DATABASE).getCollection(Constant.MONGODB_RATE_MORE_MOVIES_RECENTLY_COLLECTION);
        FindIterable<Document> documents = rateMoreMoviesRecentlyCollection.find().sort(Sorts.descending("yearmonth")).limit(request.getSum());

        List<Recommendation> recommendations = new ArrayList<>();
        for (Document document : documents) {
            recommendations.add(new Recommendation(document.getInteger("mid"), 0D));
        }
        return recommendations;
    }

    public List<Recommendation> getRateMoreRecommendations(RateMoreRecommendationRequest request) {

        // 获取评分最多电影的条目
        MongoCollection<Document> rateMoreMoviesCollection = mongoClient.getDatabase(Constant.MONGODB_DATABASE).getCollection(Constant.MONGODB_RATE_MORE_MOVIES_COLLECTION);
        FindIterable<Document> documents = rateMoreMoviesCollection.find().sort(Sorts.descending("count")).limit(request.getSum());

        List<Recommendation> recommendations = new ArrayList<>();
        for (Document document : documents) {
            recommendations.add(new Recommendation(document.getInteger("mid"), 0D));
        }
        return recommendations;
    }

    public List<Recommendation> getContentBasedGenresRecommendations(SearchRecommendationRequest request) {
        FuzzyQueryBuilder query = QueryBuilders.fuzzyQuery("genres", request.getText());
        return parseESResponse(esClient.prepareSearch().setIndices(Constant.ES_INDEX).setTypes(Constant.ES_MOVIE_TYPE).setQuery(query).setSize(request.getSum()).execute().actionGet());
    }

    public List<Recommendation> getTopGenresRecommendations(TopGenresRecommendationRequest request) {
        Document genresTopMovies = mongoClient.getDatabase(Constant.MONGODB_DATABASE).getCollection(Constant.MONGODB_GENRES_TOP_MOVIES_COLLECTION)
                .find(Filters.eq("genres", request.getGenres())).first();
        return parseRecs(genresTopMovies, request.getSum());
    }

}