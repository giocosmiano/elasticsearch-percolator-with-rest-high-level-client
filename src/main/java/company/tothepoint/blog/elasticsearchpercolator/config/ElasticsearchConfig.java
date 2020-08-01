package company.tothepoint.blog.elasticsearchpercolator.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.client.*;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.Arrays;

@Slf4j
@Configuration
public class ElasticsearchConfig {

    public static final String PERCOLATOR_INDEX = "rest_client_percolator_index";
    public static final String PERCOLATOR_INDEX_MAPPING_TYPE = "docs";


    @Value("${elastic.cluster_name}")
    private String clusterName;
    @Value("${elastic.host_url}")
    private String elasticHostUrl;
    @Value("${elastic.host_port}")
    private int elasticHostPort;
    @Value("${elastic.username}")
    private String userName;
    @Value("${elastic.password}")
    private String password;

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restClient() {

        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(userName, password));

        RestClientBuilder builder = RestClient.builder(new HttpHost(elasticHostUrl, elasticHostPort))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));

        RestHighLevelClient client = new RestHighLevelClient(builder);

        return client;

    }

    /**
     * Create the index for the percolator data if it does not exist
     */
    @PostConstruct
    public void initializePercolatorIndex() {
        try {
            RestHighLevelClient client = restClient();

            GetIndexRequest getIndexRequest = new GetIndexRequest();
            getIndexRequest.indices(PERCOLATOR_INDEX);

            if (! client.indices().exists(getIndexRequest, RequestOptions.DEFAULT)) {
                XContentBuilder percolatorQueriesMapping = XContentFactory.jsonBuilder()
                        .startObject()
                        .startObject("properties");

                Arrays.stream(PercolatorIndexFields.values())
                        .forEach(field -> {
                            try {
                                percolatorQueriesMapping
                                        .startObject(field.getFieldName())
                                        .field("type", field.getFieldType())
                                        .endObject();
                            } catch (IOException e) {
                                log.error(String.format("Error while adding field %s to mapping", field.name()), e);
                                throw new RuntimeException(
                                        String.format("Something went wrong while adding field %s to mapping", field.name()), e);
                            }
                        });

                percolatorQueriesMapping
                        .endObject()
                        .endObject();

                CreateIndexRequest createIndexRequest = new CreateIndexRequest(PERCOLATOR_INDEX);
                createIndexRequest
                        .index(PERCOLATOR_INDEX)
                        .mapping(PERCOLATOR_INDEX_MAPPING_TYPE, percolatorQueriesMapping);
                CreateIndexResponse createIndexResponse =
                        client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                log.info("createIndexResponse {}", createIndexResponse);
            }

        } catch (Exception e) {
            log.error("Error while creating percolator index", e);
            throw new RuntimeException("Something went wrong during the creation of the percolator index", e);
        }
    }
}