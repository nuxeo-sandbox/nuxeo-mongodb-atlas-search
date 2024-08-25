package org.nuxeo.labs.atlas.search.pp;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.search.SearchOperator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.nuxeo.ecm.core.api.*;
import org.nuxeo.ecm.core.security.SecurityService;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.nxql.CoreQueryDocumentPageProvider;
import org.nuxeo.labs.atlas.search.pp.facets.AtlasFacetBase;
import org.nuxeo.labs.atlas.search.pp.facets.AtlasRangeFacet;
import org.nuxeo.labs.atlas.search.pp.facets.AtlasTermFacet;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.mongodb.MongoDBConnectionConfig;
import org.nuxeo.runtime.mongodb.MongoDBConnectionService;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.search.SearchCount;
import com.mongodb.client.model.search.SearchOptions;

import java.util.*;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_READ_ACL;
import static org.nuxeo.ecm.platform.query.api.PageProviderService.NAMED_PARAMETERS;

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

    @Override
    protected void pageChanged() {
        currentPageDocuments = null;
        currentAggregates = null;
        super.pageChanged();
    }

    @Override
    public void refresh() {
        currentPageDocuments = null;
        currentAggregates = null;
        super.refresh();
    }


    public MongoCollection<Document> getCollection(CoreSession session) {
        String repositoryName = session.getRepositoryName();
        MongoDBConnectionService mongoService = Framework.getService(MongoDBConnectionService.class);
        MongoDBConnectionConfig config = mongoService.getConfig(repositoryName);
        MongoDatabase database = mongoService.getDatabase(config.dbname);
        return database.getCollection(repositoryName);
    }

    public int extractCounts(Document result) {
        if (!result.containsKey("meta")) {
            return 0;
        }
        BsonDocument meta = result.toBsonDocument().getDocument("meta");
        if (meta == null) {
            return 0;
        }
        BsonDocument count = meta.getDocument("count");
        if (count == null) {
            return 0;
        }
        return count.getInt64("total").intValue();
    }

    public List<DocumentModel> extractHits(CoreSession session, Document result) {
        if (!result.containsKey("docs")) {
            return new ArrayList<>();
        }
        List<Document> docs = result.getList("docs", Document.class);
        IdRef[] ids = docs.stream().map(doc -> doc.toBsonDocument().getString("ecm:id").getValue()).map(IdRef::new).toArray(IdRef[]::new);
        if (ids.length == 0) {
            return new ArrayList<>();
        }

        DocumentModelList list =  session.getDocuments(ids);

        return docs.stream().map(entry -> {
            BsonDocument hit = entry.toBsonDocument();
            String id = hit.getString("ecm:id").getValue();
            double score = hit.getDouble("_score").getValue();
            DocumentModel documentModel = list.stream().filter(doc -> id.equals(doc.getId())).findFirst().get();
            documentModel.putContextData("score", score);
            return documentModel;
        }).toList();
    }

    public AtlasFacetBase getAggregate(String name) {
        List<AggregateDefinition> aggregateDefinitions = getAggregateDefinitions();
        AggregateDefinition def = aggregateDefinitions.stream().filter(predicate -> name.equals(predicate.getId())).findFirst().orElse(null);
        return switch(def.getType()) {
            case "terms" -> new AtlasTermFacet(def,getSearchDocumentModel());
            case "range" -> new AtlasRangeFacet(def,getSearchDocumentModel());
            default -> null;
        };
    }

    public HashMap<String, Aggregate<? extends Bucket>> extractFacetBuckets(Document result) {
        HashMap<String, Aggregate<? extends Bucket>> aggregates = new HashMap<>();

        if (!result.containsKey("meta")) {
            return aggregates;
        }

        BsonDocument meta = result.toBsonDocument().getDocument("meta");
        if (meta == null) {
            return aggregates;
        }
        BsonDocument facets = meta.getDocument("facet");
        if (facets == null) {
            return aggregates;
        }

        for (Map.Entry<String, BsonValue> facet : facets.toBsonDocument().entrySet()) {
            String name = facet.getKey();
            AtlasFacetBase<Bucket> atlasFacet = getAggregate(name);
            atlasFacet.parseAggregation(facet.getValue());
            aggregates.put(name, atlasFacet);
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
            AtlasFacetBase atlasFacet = getAggregate(def.getId());
            Document facet = atlasFacet.getFacet();
            if (facet != null) {
                facets.append(def.getId(), facet);
            }
        }
        return facets;
    }

    public List<SearchOperator> buildFacetFilter() {
        List<SearchOperator> filters = new ArrayList<>();
        for (AggregateDefinition def : getAggregateDefinitions()) {
            AtlasFacetBase atlasFacet = getAggregate(def.getId());
            SearchOperator filter = atlasFacet.getSelectionFilter();
            if (filter != null) {
                filters.add(filter);
            }
        }
        return filters;
    }

    protected SearchOperator getSecurityFilter() {
        NuxeoPrincipal principal = getCoreSession().getPrincipal();
        if (principal == null || principal.isAdministrator()) {
            return null;
        }
        List<String> principals = Arrays.asList(SecurityService.getPrincipalsToCheck(principal));
        return SearchOperator.of(new Document("in",  new Document("path", KEY_READ_ACL).append("value", principals)));
    }

    public void search() {
        DocumentModel searchDoc = getSearchDocumentModel();
        Map<String, String> namedParameters = (Map<String, String>) searchDoc.getContextData(NAMED_PARAMETERS);

        buildQuery(getCoreSession());

        log.debug(query);

        SearchOperator nxqlSearchOp = MongoDBAtlasSearchQueryConverter.toAtlasQuery(query,getCoreSession());

        log.debug(format(nxqlSearchOp.toBsonDocument()));

        //set permission filters
        SearchOperator permissionFilter = getSecurityFilter();
        if (permissionFilter != null) {
            nxqlSearchOp = SearchOperator.compound().must(List.of(nxqlSearchOp)).filter(List.of(permissionFilter));
        }

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
                sortOption.append(sortInfo.getSortColumn(), sortInfo.getSortAscending() ? 1 : -1);
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
            log.debug(format(operator.toBsonDocument()));
        } else {
            operator = nxqlSearchOp;
        }

        Bson searchStage = Aggregates.search(operator, searchOptions);
        log.debug(format(searchStage.toBsonDocument()));
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

        log.debug(format(facetStage.toBsonDocument()));
        stages.add(facetStage);

        //set stage
        Bson setStage = new Document("$set",
                new Document("meta",
                        new Document("$arrayElemAt",
                                Arrays.asList("$meta", 0))));

        log.debug(format(setStage.toBsonDocument()));

        stages.add(setStage);

        //do search
        AggregateIterable<Document> aggregationResults = collection.aggregate(stages);

        Document first = aggregationResults.first();

        log.debug(format(first.toBsonDocument()));

        //set results
        setResultsCount(extractCounts(first));

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
