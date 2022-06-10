package com.koi.bigdata.business;


import com.koi.bigdata.business.utils.Constant;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ExecutionException;

import static com.koi.bigdata.business.utils.Constant.URL;


public class Application {

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {

        ApplicationContext context = new ClassPathXmlApplicationContext("classpath:application.xml");

        Settings settings = Settings.builder().put("cluster.name", "es-cluster").build();
        TransportClient esClient = new PreBuiltTransportClient(settings);
        esClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(URL), 9300));
        GetResponse getResponse = esClient.prepareGet(Constant.ES_INDEX, Constant.ES_MOVIE_TYPE, "3062").get();

        Object value = getResponse.getSourceAsMap().get("tags");

        if (value == null) {
            UpdateRequest updateRequest = new UpdateRequest(Constant.ES_INDEX, Constant.ES_MOVIE_TYPE, "3062");
            updateRequest.doc(XContentFactory.jsonBuilder().startObject()
                    .field("tags", "abc")
                    .endObject());
            esClient.update(updateRequest).get();
        } else {
            UpdateRequest updateRequest = new UpdateRequest(Constant.ES_INDEX, Constant.ES_MOVIE_TYPE, "2542");
            updateRequest.doc(XContentFactory.jsonBuilder().startObject()
                    .field("tags", value + "|abc")
                    .endObject());
            esClient.update(updateRequest).get();
        }
    }
}
