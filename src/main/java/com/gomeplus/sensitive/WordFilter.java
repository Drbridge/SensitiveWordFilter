package com.gomeplus.sensitive;

/**
 * Created by wangxiaojing on 2016/9/19.
 */


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse.*;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;

import static org.elasticsearch.common.xcontent.XContentFactory.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.gomeplus.util.Conf;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;


public class WordFilter {

    private final static String WORD = "word";

    private final static String GOME = "gome";

    private final static String DATA_DIR = "data/word";

    private final static String CHARSET = "UTF-8";

    private final static int ES_PORT = 9300;

    private final static String ikMain = "ik_main";

    //创建Es客户端
    private static TransportClient client;

    private Logger loggers;

    private Conf conf;

    // redis 配置
    private JedisCluster jc = null;

    /**
     * 构造函数，负责读取配置文件，完成Es设置
     */
    public WordFilter() {
        conf = new Conf();
        loggers = LoggerFactory.getLogger(WordFilter.class);
        String[] esHostname = conf.getEsHostname().split(",");
        String clusterName = conf.getEsClusterName();
        InetSocketAddress inetSocketAddress = null;
        for (String hostname : esHostname) {
            inetSocketAddress = new InetSocketAddress(hostname, ES_PORT);
        }
        Settings settings = Settings.settingsBuilder()
                .put("cluster.name", clusterName).build();
        client = TransportClient.builder().settings(settings).build()
                .addTransportAddress(new InetSocketTransportAddress(inetSocketAddress));

        // redis 创建
        String[] redisHosts = conf.getRedisHosts().split(";");
        Set<HostAndPort> hps = new HashSet<HostAndPort>();
        for (String redisHost : redisHosts) {
            String[] hp = redisHost.split(":");
            hps.add(new HostAndPort(hp[0], Integer.valueOf(hp[1]).intValue()));
        }
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setJmxEnabled(false);
        loggers.debug(hps.toString());
        loggers.debug("start connect redis");
        jc = new JedisCluster(hps, 2000, 10, poolConfig);
    }

    /**
     * 创建Es索引,id自动添加
     *
     * @param str 敏感词
     */
    public void createIndex(String str) {
        if (!str.isEmpty()) {
            //创建数据内容
            try {
                String word = new String(str.getBytes("UTF-8"), CHARSET);
                XContentBuilder builder = jsonBuilder()
                        .startObject()
                        .field("word", word)
                        .endObject();
                //创建es索引
                IndexResponse response = client.prepareIndex(GOME, WORD).setSource(builder).get();
                // 持久化到ik库，当重启时能继续加载已更新热词
                jc.sadd(ikMain, word);
                // 添加redid订阅内，完成热词更新操作
                jc.publish(ikMain,word);
                loggers.info(response.getId() + "   index: " + response.getIndex() + " word:  " + word);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 通过指定目录创建es索引
     * 需要文件格式是 UTF-8 编码
     * 所有文件存储在 data目录下
     */
    public void fileCreateIndex() {
        File dataDir = new File(DATA_DIR);
        if (dataDir.exists() & dataDir.isDirectory()) {
            // 获取目录下文件列表
            String[] children = dataDir.list();
            for (String fileName : children) {
                File dataFile = new File(DATA_DIR, fileName);
                if (dataFile.exists() && dataFile.isFile()) {
                    try {
                        FileInputStream fis = new FileInputStream(dataFile);
                        InputStreamReader isr = new InputStreamReader(fis, CHARSET);
                        BufferedReader reader = new BufferedReader(isr);
                        String tempString = null;
                        // 按行读取文件内容
                        while ((tempString = reader.readLine()) != null) {
                            String word = tempString.trim();
                            //一行创建一个敏感词的的索引,如果敏感词库中已经包含该词，则不再继续创建索引
                            String exitSensitiveWord = searchWord(word);
                            if (null == exitSensitiveWord) {
                                createIndex(word);
                            }
                        }
                        isr.close();
                        fis.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            loggers.info("Dir is not Exit");
        }
    }

    /**
     *
     * 搜索ES关键词
     *
     * @param str 被搜索的词
     * @return 如果存在敏感词，返回true，否则返回false
     */
    public String searchWord(String str) {
        //如果查询字符不为空
        String result = null;
        if (str != null & !str.isEmpty()) {
            try {
                // 直接使用termQuery 无法查询中文
                //QueryBuilders.termQuery("word", str.trim());
                QueryBuilder queryBuilder = QueryBuilders.matchPhraseQuery("word", str.trim());
                SearchResponse response = client.prepareSearch(GOME).setTypes(WORD)
                        .setQuery(queryBuilder).execute().actionGet();
                SearchHits hits = response.getHits();
                //如果搜索到关键词，那么就意味着这个词是敏感词
                if (hits.totalHits() > 0) {
                    for (SearchHit hit : hits) {
                        String word = hit.getSource().get("word").toString();
                        // 如果查找到立刻返回，不在做过多的判断
                        if (hit.getSource().containsValue(str)&&word.equals(str)) {
                            loggers.info("Index is : " + hit.getIndex() +
                                    "ID is: " + hit.getId() + " type is:" + hit.getType() + " word is : " + word);
                            result = hit.getId();
                            return result;
                        }
                    }
                }
            } catch (IndexNotFoundException e) {
                e.printStackTrace();
            }

        }
        return result;
    }

    /**
     * 查询词某个单词
     *
     * @param str  待查询的词
     * @return 如果正常返回SearchHits，否则返回null
     * */
    public SearchHits searchAllWord(String str) {
        //如果查询字符不为空
        SearchHits result = null;
        if (str != null & !str.isEmpty()) {
            try {
                // 直接使用termQuery 无法查询中文
                //QueryBuilders.termQuery("word", str.trim());
                QueryBuilder queryBuilder = QueryBuilders.matchPhraseQuery("word", str.trim());
                SearchResponse response = client.prepareSearch(GOME).setTypes(WORD)
                        .setQuery(queryBuilder).execute().actionGet();
                SearchHits hits = response.getHits();
                //如果搜索到关键词，那么就意味着这个词是敏感词
                if (hits.totalHits() > 0) {
                   result = hits;
                }
            } catch (IndexNotFoundException e) {
                e.printStackTrace();
            }
        }
        return  result;
    }

    /**
     * 对输入文档进行中文分词操作，递归查询每个分词是否是敏感词，
     *
     * @return : 当存在敏感词语句是返回true，否则返回false
     */
    public boolean semanticAnalysis(String text) {
        boolean result = false;
        if (text != null & !text.isEmpty()) {
            AnalyzeResponse analyzeResponse = client.admin().indices().prepareAnalyze(text)
                    .setAnalyzer("ik_smart").execute().actionGet();
            List<AnalyzeToken> list = analyzeResponse.getTokens();
            if (list.isEmpty()) {
                return true;
            } else {
                for (AnalyzeToken analyzeToken : list) {
                    String word = analyzeToken.getTerm();
                    //如果是敏感词
                    String isSensitive = searchWord(word);
                    if (null!=isSensitive) {
                        loggers.info(word);
                        int startOffset = analyzeToken.getStartOffset();
                        int endOffset = analyzeToken.getEndOffset();
                        //递归查询是否是敏感词
                        for (int forward = 0; forward >= -2; forward--) {
                            for (int backward = 1; backward <= 2; backward++) {
                                int newStartOffset = (startOffset + forward) < 0 ? 0 : startOffset + forward;
                                int newEndOffset = (endOffset + backward) > text.length()
                                        ? text.length() : endOffset + backward;
                                String newWord = text.substring(newStartOffset, newEndOffset);
                                String newWordIsSensitive = searchWord(newWord);
                                // 是敏感词则返回true
                                if (null != newWordIsSensitive) {
                                    result = true;
                                    return result;
                                }
                            }
                        }
                        loggers.info("Analyze Sensitive word : " + word);
                        result = true;
                        return result;
                    }
                    loggers.info("Term :" + analyzeToken.getTerm() + "\t position : " + analyzeToken.getPosition());
                }
                return result;
            }
        } else {
            return result;
        }
    }


    /**
     * 删除ES整个索引库,即删除整个库
     * @param indexName  索引库名称
     * */
    public boolean deleteEsIndex(String indexName){
        boolean deleteRequest = true;
        // 判断index是否存在
        IndicesExistsRequest inExistsRequest = new IndicesExistsRequest(indexName);
        IndicesExistsResponse inExistsResponse = client.admin().indices()
                .exists(inExistsRequest).actionGet();
        if(inExistsResponse.isExists()){
            DeleteIndexResponse dResponse = client.admin().indices().prepareDelete(indexName).execute().actionGet();
            deleteRequest = dResponse.isAcknowledged();
        }else {
            loggers.info("This index is not Exist");
        }
        return  deleteRequest;
    }

    /**
     * 删除Es指定数据，目前测试不成功
     * @param id  ID名称
     */
    public boolean deleteEs(String id) {
        boolean deleteRequest = false;
        if(null != id){
            String isExitWordID = searchWord(id);
            if(null!=isExitWordID){
                DeleteResponse dResponse = client.prepareDelete(GOME, WORD, isExitWordID).execute().actionGet();
                loggers.info("Delete Es ID :" + id + " " + dResponse.isFound());
                deleteRequest = dResponse.isFound();
                //删除redis 集合中敏感词词词典
                jc.srem(ikMain,id);
            }
        }
        return  deleteRequest;
    }

    /**
     * 将text转换成json格式，并从中获取文本信息
     * */
    public String getText(String text){
        if(null != text){
            JSONObject jsonObject = JSON.parseObject(new String(text.toString()));
            String[] jsonText = conf.getJsonText().split(",");
            int size = jsonText.length;
            for(int i = 0 ; i <= size -2 ;i++){
                jsonObject = jsonObject.getJSONObject(jsonText[i]);
            }
            String sensitiveCheck = jsonObject.getString(jsonText[size-1]);
            return sensitiveCheck;
        }
        return  null;
    }

    public void getEs(){
        GetRequestBuilder getRequestBuilder =  client.prepareGet().setIndex(GOME).setType(WORD);
        loggers.info(getRequestBuilder.toString());
    }
}
