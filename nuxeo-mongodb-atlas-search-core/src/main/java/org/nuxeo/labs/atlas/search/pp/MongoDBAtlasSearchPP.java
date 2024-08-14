package org.nuxeo.labs.atlas.search.pp;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.query.nxql.CoreQueryDocumentPageProvider;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.mongodb.MongoDBConnectionService;

import java.util.ArrayList;
import java.util.List;

public class MongoDBAtlasSearchPP extends CoreQueryDocumentPageProvider {

    private static final Logger log = LogManager.getLogger(MongoDBAtlasSearchPP.class);

    @Override
    public List<DocumentModel> getCurrentPage() {
        MongoDBConnectionService mongoService = Framework.getService(MongoDBConnectionService.class);
        MongoDatabase database = mongoService.getDatabase("nuxeo");
        MongoCollection<Document> collection = database.getCollection("default");
        return new ArrayList<>();
    }

}
