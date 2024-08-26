package org.nuxeo.labs.atlas.search.pp.facets;

import com.mongodb.client.model.search.SearchOperator;
import org.bson.BsonValue;
import org.bson.Document;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.impl.ArrayProperty;
import org.nuxeo.ecm.core.api.model.impl.ListProperty;
import org.nuxeo.ecm.platform.query.api.AggregateDefinition;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.ecm.platform.query.core.AggregateBase;

import java.util.Arrays;
import java.util.List;

public abstract class AtlasFacetBase<B extends Bucket> extends AggregateBase<B> {

    public static final char XPATH_SEP = '/';

    public static final char ES_MUTLI_LEVEL_SEP = '.';

    public static final int MAX_AGG_SIZE = 1000;

    public AtlasFacetBase(AggregateDefinition definition, DocumentModel searchDocument) {
        super(definition, searchDocument);
    }

    /**
     * Return the mongodb atlas search facet builder
     */
    public abstract Document getFacet();

    /**
     * Return the MongoDB atlas search aggregate filter corresponding to the selection
     */
    public abstract SearchOperator getSelectionFilter();

    /**
     * Extract the aggregation from the atlas search response
     */
    public abstract void parseAggregation(BsonValue facet);


    @Override
    public String getField() {
        String ret = super.getField();
        ret = ret.replace(XPATH_SEP, ES_MUTLI_LEVEL_SEP);
        return ret;
    }

    protected int getAggSize(String prop) {
        // handle the size = 0 which means all terms in ES 2 and which is not supported in ES 5
        int size = Integer.parseInt(prop);
        return size == 0 ? MAX_AGG_SIZE : size;
    }

    @Override
    public String getXPathField() {
        String ret = super.getField();
        return ret.replace(ES_MUTLI_LEVEL_SEP, XPATH_SEP);
    }

    public List<String> getValues() {
        Property prop = searchDocument.getPropertyObject(
                definition.getSearchField().getSchema(),
                definition.getSearchField().getName());

        if (prop == null) {
            return null;
        } else if (prop instanceof ArrayProperty) {
            String[] array = (String[]) prop.getValue();
            return array != null ? Arrays.asList(array) : null;
        } else if (prop instanceof ListProperty) {
            return (List<String>) prop.getValue();
        } else {
            return null;
        }
    }
}
