package org.nuxeo.labs.atlas.search.pp;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.search.SearchOperator;
import com.mongodb.client.model.search.SearchPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.nuxeo.ecm.core.api.*;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.core.AggregateBase;
import org.nuxeo.ecm.platform.query.core.BucketTerm;
import org.nuxeo.ecm.platform.query.nxql.CoreQueryDocumentPageProvider;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.mongodb.MongoDBConnectionConfig;
import org.nuxeo.runtime.mongodb.MongoDBConnectionService;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.search.SearchCount;
import com.mongodb.client.model.search.SearchOptions;

import java.util.*;

import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;
import static org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchQueryConverter.getFieldName;

public class MongoDBAtlasSearchPP extends CoreQueryDocumentPageProvider {

    private static final Logger log = LogManager.getLogger(MongoDBAtlasSearchPP.class);

    protected HashMap<String, Aggregate<? extends Bucket>> currentAggregates;

    @Override
    public List<DocumentModel> getCurrentPage() {
        long t0 = System.currentTimeMillis();

        // use a cache
        if (currentPageDocuments != null) {
            return currentPageDocuments;
        }

        search();

        // send event for statistics !
        fireSearchEvent(getCoreSession().getPrincipal(), query, currentPageDocuments, System.currentTimeMillis() - t0);
        return currentPageDocuments;
    }

    @Override
    public boolean hasAggregateSupport() {
        return true;
    }

    @Override
    public Map<String, Aggregate<? extends Bucket>> getAggregates() {
        getCurrentPage();
        return currentAggregates;
    }

    public MongoCollection<Document> getCollection(CoreSession session) {
        String repositoryName = session.getRepositoryName();
        MongoDBConnectionService mongoService = Framework.getService(MongoDBConnectionService.class);
        MongoDBConnectionConfig config = mongoService.getConfig(repositoryName);
        MongoDatabase database = mongoService.getDatabase(config.dbname);
        return database.getCollection(repositoryName);
    }

    public List<DocumentModel> extractHits(CoreSession session, Document result) {
        List<DocumentModel> hits;
        List<Document> docs = result.getList("docs", Document.class);
        IdRef[] ids = docs.stream().map(doc -> doc.toBsonDocument().getString("ecm:id").getValue()).map(IdRef::new).toArray(IdRef[]::new);
        hits = session.getDocuments(ids);
        return hits;
    }

    public HashMap<String, Aggregate<? extends Bucket>> extractFacetBuckets(Document result) {
        HashMap<String, Aggregate<? extends Bucket>> aggregates = new HashMap<>();

        BsonDocument meta = result.toBsonDocument().getDocument("meta");
        if (meta == null) {
            return aggregates;
        }
        BsonDocument facets = meta.getDocument("facet");
        if (facets == null) {
            return aggregates;
        }

        List<AggregateDefinition> aggregateDefinitions = getAggregateDefinitions();

        for (Map.Entry<String, BsonValue> facet : facets.toBsonDocument().entrySet()) {
            String name = facet.getKey();
            BsonValue[] buckets = facet.getValue().asDocument().getArray("buckets").toArray(BsonValue[]::new);
            List<BucketTerm> parsedBucket = Arrays.stream(buckets).map(
                            bucket -> new BucketTerm(
                                    bucket.asDocument().getString("_id").getValue(),
                                    bucket.asDocument().getInt64("count").getValue())
                    )
                    .toList();
            AggregateDefinition def = aggregateDefinitions.stream().filter(predicate -> name.equals(predicate.getId())).findFirst().orElse(null);
            AggregateBase<BucketTerm> base = new AggregateBase<>(def, getSearchDocumentModel());
            base.setBuckets(parsedBucket);
            aggregates.put(name, base);
        }

        return aggregates;
    }

    public boolean runWithFacets() {
        List<AggregateDefinition> aggregateDefinitions = getAggregateDefinitions();
        boolean skip = isSkipAggregates();
        return !(skip || aggregateDefinitions.isEmpty());
    }

    public Document buildFacets() {
        Document facets = new Document();
        for (AggregateDefinition def : getAggregateDefinitions()) {
            //todo handle all types of facets
            if(!"terms".equals(def.getType())) {
                continue;
            }
            facets.append(def.getId(), new Document("type", "string").append("path", getFieldName(def.getDocumentField(),null)));
        }
        return facets;
    }

    public List<SearchOperator> buildFacetFilter() {
        List<SearchOperator> filters = new ArrayList<>();
        for (AggregateDefinition def : getAggregateDefinitions()) {
            //todo handle all types of facets
            if(!"terms".equals(def.getType())) {
                continue;
            }
            DocumentModel searchDoc = getSearchDocumentModel();
            List<String> values = (List<String>) searchDoc.getProperty(def.getSearchField().getSchema(),def.getSearchField().getName());
            if (values != null && !values.isEmpty()) {
                filters.add(
                        SearchOperator.of(new Document("equals",
                                new Document("path", getFieldName(def.getDocumentField(),null)).append("value", values))));
            }
        }
        return filters;
    }

    public void search() {
        DocumentModel searchDoc = getSearchDocumentModel();
        Map<String, String> namedParameters = (Map<String, String>) searchDoc.getContextData(NAMED_PARAMETERS);

        buildQuery(getCoreSession());

        System.out.println(query);

        SearchOperator nxqlSearchOp =MongoDBAtlasSearchQueryConverter.toAtlasQuery(query,getCoreSession());
        System.out.println(format(nxqlSearchOp.toBsonDocument()));

        CoreSession session = getCoreSession();
        MongoCollection<Document> collection = getCollection(session);

        List<Bson> stages = new ArrayList<>();

        // $search stage
        SearchOptions searchOptions = SearchOptions.searchOptions()
                .index("default")
                .count(SearchCount.total());

        if (!getSortInfos().isEmpty()) {
            Document sortOption = new Document();
            for(SortInfo sortInfo : getSortInfos()) {
                sortOption.append(sortInfo.getSortColumn(), sortInfo.getSortAscending() ? 1 : 0);
            }
            searchOptions = searchOptions.option("sort",sortOption);
        }

        SearchOperator operator;

        if (runWithFacets()) {
            SearchOperator innerOp = nxqlSearchOp;
            List<SearchOperator> facetFilters = buildFacetFilter();
            if (!facetFilters.isEmpty()) {
                innerOp = SearchOperator.compound().must(List.of(innerOp)).filter(facetFilters);
            }
            operator = SearchOperator.of(
                    new Document("facet",
                            new Document("operator", innerOp)
                                    .append("facets", buildFacets())));
            System.out.println(format(operator.toBsonDocument()));
        } else {
            operator = nxqlSearchOp;
        }

        Bson searchStage = Aggregates.search(operator, searchOptions);
        System.out.println(format(searchStage.toBsonDocument()));
        stages.add(searchStage);

        // $facet stage
        List<Bson> facetStages = new ArrayList<>();
        facetStages.add(Aggregates.skip((int) getCurrentPageOffset()));
        facetStages.add(Aggregates.limit((int) getPageSize()));

        //Set document fields to include in the response
        List<Bson> projections = new ArrayList<>();
        projections.add(Projections.excludeId());
        projections.add(Projections.include("ecm:id"));
        projections.add(Projections.metaSearchScore("_score"));

        facetStages.add(Aggregates.project(Projections.fields(projections)));
        Bson facetStage = new Document("$facet",
                new Document("docs", facetStages)
                        .append("meta",
                                Arrays.asList(new Document("$replaceWith", "$$SEARCH_META"), Aggregates.limit(1)))
        );

        System.out.println(format(facetStage.toBsonDocument()));
        stages.add(facetStage);

        //set stage
        Bson setStage = new Document("$set",
                new Document("meta",
                        new Document("$arrayElemAt",
                                Arrays.asList("$meta", 0))));

        System.out.println(format(setStage.toBsonDocument()));

        stages.add(setStage);

        //do search
        AggregateIterable<Document> aggregationResults = collection.aggregate(stages);

        Document first = aggregationResults.first();

        System.out.println(format(first.toBsonDocument()));

        currentPageDocuments = extractHits(session, first);

        if (runWithFacets()) {
            currentAggregates = extractFacetBuckets(first);
        }
    }

    private static String format(BsonDocument document) {
        var settings = JsonWriterSettings.builder()
                .indent(true).outputMode(JsonMode.SHELL).build();
        return document.toJson(settings);
    }

}
