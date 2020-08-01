package company.tothepoint.blog.elasticsearchpercolator.service;

import company.tothepoint.blog.elasticsearchpercolator.config.PercolatorIndexFields;
import company.tothepoint.blog.elasticsearchpercolator.domain.Book;
import company.tothepoint.blog.elasticsearchpercolator.domain.SearchPreference;
import company.tothepoint.blog.elasticsearchpercolator.repository.BookRepository;
import company.tothepoint.blog.elasticsearchpercolator.repository.SearchPreferenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.percolator.PercolateQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import static company.tothepoint.blog.elasticsearchpercolator.config.ElasticsearchConfig.*;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

@Slf4j
@Service
public class BookstoreService {

    private final BookRepository bookRepository;

    private final SearchPreferenceRepository searchPreferenceRepository;

    private final RestHighLevelClient elasticsearchClient;

    public BookstoreService(BookRepository bookRepository,
                            SearchPreferenceRepository searchPreferenceRepository,
                            RestHighLevelClient elasticsearchClient) {
        this.bookRepository = bookRepository;
        this.searchPreferenceRepository = searchPreferenceRepository;
        this.elasticsearchClient = elasticsearchClient;
    }

    public SearchPreference createSearchPreference(SearchPreference searchPreference) throws IOException {
        SearchPreference savedPreference = searchPreferenceRepository.save(searchPreference);

        BoolQueryBuilder bqb = createBoolQuery(savedPreference);

        IndexRequest indexRequest = new IndexRequest(PERCOLATOR_INDEX, PERCOLATOR_INDEX_MAPPING_TYPE, savedPreference.getSearchPreferenceId())
                .source(jsonBuilder()
                        .startObject()
                        .field(PercolatorIndexFields.PERCOLATOR_QUERY.getFieldName(), bqb) // Register the query
                        .endObject())
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE); // Needed when the query shall be available immediately

        IndexResponse indexResponse = elasticsearchClient.index(indexRequest, RequestOptions.DEFAULT);

        log.info("indexResponse {}", indexResponse);

        return savedPreference;
    }

    public Collection<SearchPreference> findMatchingPreferences(String bookId) throws IOException {
        Collection<SearchPreference> results = new ArrayList<>();
        Book aBook = bookRepository.findOne(bookId);

        if (aBook != null) {
            PercolateQueryBuilder percolateQuery = createPercolateQuery(aBook);

            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(percolateQuery);

            SearchRequest searchRequest = new SearchRequest();
            searchRequest
                    .indices(PERCOLATOR_INDEX)
                    .types(PERCOLATOR_INDEX_MAPPING_TYPE)
                    .source(searchSourceBuilder);


            SearchResponse searchResponse = elasticsearchClient.search(searchRequest, RequestOptions.DEFAULT);

            if (searchResponse != null) {
                SearchHits searchHits = searchResponse.getHits();
                if (searchHits != null && searchHits.getTotalHits() > 0) {

                    for (SearchHit hit : searchHits.getHits()) {
                        results.add(searchPreferenceRepository.findOne(hit.getId()));
                    }
                }
            }
        }
        return results;
    }


    private BoolQueryBuilder createBoolQuery(SearchPreference preference) {
        BoolQueryBuilder bqb = QueryBuilders.boolQuery();

        if (preference.getCriteria().getAuthor() != null) {
            bqb.filter(QueryBuilders.termsQuery(PercolatorIndexFields.AUTHOR.getFieldName(), preference.getCriteria().getAuthor()));
        }

        if (preference.getCriteria().getTypes() != null) {
            bqb.filter(QueryBuilders.termsQuery(PercolatorIndexFields.TYPE.getFieldName(), preference.getCriteria().getTypes()));
        }

        if (preference.getCriteria().getLanguage() != null) {
            bqb.filter(QueryBuilders.termsQuery(PercolatorIndexFields.LANGUAGE.getFieldName(), preference.getCriteria().getLanguage()));
        }

        if (preference.getCriteria().getMinimumPrice() != null && preference.getCriteria().getMaximumPrice() != null) {
            bqb.filter(
                    QueryBuilders.rangeQuery(PercolatorIndexFields.PRICE.getFieldName())
                            .gte(preference.getCriteria().getMinimumPrice().doubleValue())
                            .lte(preference.getCriteria().getMaximumPrice().doubleValue()));
        } else if (preference.getCriteria().getMinimumPrice() != null) {
            bqb.filter(
                    QueryBuilders.rangeQuery(PercolatorIndexFields.PRICE.getFieldName())
                            .gte(preference.getCriteria().getMinimumPrice().doubleValue()));
        } else if (preference.getCriteria().getMaximumPrice() != null) {
            bqb.filter(QueryBuilders.rangeQuery(PercolatorIndexFields.PRICE.getFieldName())
                    .lte(preference.getCriteria().getMaximumPrice().doubleValue()));
        }

        return bqb;
    }


    private PercolateQueryBuilder createPercolateQuery(Book book) throws IOException {
        //Build a document to check against the percolator
        XContentBuilder docBuilder = XContentFactory.jsonBuilder().startObject();
        docBuilder.field(PercolatorIndexFields.AUTHOR.getFieldName(), book.getAuthor());
        docBuilder.field(PercolatorIndexFields.LANGUAGE.getFieldName(), book.getLanguage().name());
        docBuilder.field(PercolatorIndexFields.PRICE.getFieldName(), book.getPrice());
        docBuilder.field(PercolatorIndexFields.TYPE.getFieldName(), book.getType());
        docBuilder.endObject();

        return new PercolateQueryBuilder(PercolatorIndexFields.PERCOLATOR_QUERY.getFieldName(),
                BytesReference.bytes(docBuilder),
                XContentType.JSON);
    }
}
