package org.nuxeo.labs.atlas.search.pp;

import com.mongodb.client.model.search.CompoundSearchOperator;
import com.mongodb.client.model.search.SearchOperator;

import com.mongodb.client.model.search.SearchPath;
import org.bson.Document;
import org.nuxeo.ecm.core.api.*;
import org.nuxeo.ecm.core.query.QueryParseException;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.query.sql.SQLQueryParser;
import org.nuxeo.ecm.core.query.sql.model.*;
import org.nuxeo.ecm.core.schema.DocumentType;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.primitives.BooleanType;
import org.nuxeo.ecm.core.schema.utils.DateParser;
import org.nuxeo.ecm.core.security.SecurityService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.ecm.core.storage.sql.jdbc.NXQLQueryMaker;

import java.io.StringReader;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.*;

public class MongoDBAtlasSearchQueryConverter {

    public static final String SELECT_ALL = "SELECT * FROM Document";

    public static final String SELECT_ALL_WHERE = "SELECT * FROM Document WHERE ";


    private MongoDBAtlasSearchQueryConverter() {
    }

    public static SearchOperator toAtlasQuery(final String nxql) {
        return toAtlasQuery(nxql, null);
    }

    public static SearchOperator toAtlasQuery(final String nxql, final CoreSession session) {
        final LinkedList<ExpressionBuilder> builders = new LinkedList<>();
        SQLQuery nxqlQuery = getSqlQuery(nxql);
        if (session != null) {
            nxqlQuery = addSecurityPolicy(session, nxqlQuery);
        }
        final ExpressionBuilder ret = new ExpressionBuilder(null);
        builders.add(ret);
        final ArrayList<String> fromList = new ArrayList<>();
        nxqlQuery.accept(new DefaultQueryVisitor() {

            @Override
            public void visitFromClause(FromClause node) {
                FromList elements = node.elements;
                SchemaManager schemaManager = Framework.getService(SchemaManager.class);

                for (String type : elements.values()) {
                    if (NXQLQueryMaker.TYPE_DOCUMENT.equalsIgnoreCase(type)) {
                        // From Document means all doc types
                        fromList.clear();
                        return;
                    }
                    Set<String> types = schemaManager.getDocumentTypeNamesExtending(type);
                    if (types != null) {
                        fromList.addAll(types);
                    }
                }
            }

            @Override
            public void visitMultiExpression(MultiExpression node) {
                for (Iterator<Predicate> it = node.predicates.iterator(); it.hasNext(); ) {
                    it.next().accept(this);
                    if (it.hasNext()) {
                        node.operator.accept(this);
                    }
                }
            }

            @Override
            public void visitSelectClause(SelectClause node) {
                // NOP
            }

            @Override
            public void visitExpression(Expression node) {
                Operator op = node.operator;
                if (op == Operator.AND || op == Operator.OR || op == Operator.NOT) {
                    builders.add(new ExpressionBuilder(op.toString()));
                    super.visitExpression(node);
                    ExpressionBuilder expr = builders.removeLast();
                    if (!builders.isEmpty()) {
                        builders.getLast().merge(expr);
                    }
                } else {
                    Reference ref = node.lvalue instanceof Reference ? (Reference) node.lvalue : null;
                    String name = ref != null ? ref.name : node.lvalue.toString();
                    Object value = null;
                    if  (node.rvalue instanceof DateLiteral) {
                        value = Date.from(((DateLiteral) node.rvalue).value.toInstant());
                    } else if (node.rvalue instanceof DoubleLiteral) {
                        value = ((DoubleLiteral) node.rvalue).value;
                    } else if (node.rvalue instanceof IntegerLiteral) {
                        value = ((IntegerLiteral) node.rvalue).value;
                    } else if  (node.rvalue instanceof BooleanLiteral) {
                        value = ((BooleanLiteral) node.rvalue).value;
                    } else if (node.rvalue instanceof Literal) {
                        value = ((Literal) node.rvalue).asString();
                    } else if (node.rvalue instanceof Function) {
                        Function function = (Function) node.rvalue;
                        String func = function.name;
                        if (NXQL.NOW_FUNCTION.equalsIgnoreCase(func)) {
                            String periodAndDurationText;
                            if (function.args == null || function.args.size() != 1) {
                                periodAndDurationText = null;
                            } else {
                                periodAndDurationText = ((StringLiteral) function.args.get(0)).value;
                            }
                            ZonedDateTime dateTime = NXQL.nowPlusPeriodAndDuration(periodAndDurationText);
                            Calendar calendar = GregorianCalendar.from(dateTime);
                            value = DateParser.formatW3CDateTime(calendar);
                        } else {
                            throw new IllegalArgumentException("Unknown function: " + func);
                        }
                    } else if (node.rvalue != null) {
                        value = node.rvalue.toString();
                    }
                    Object[] values = null;
                    if (node.rvalue instanceof LiteralList) {
                        LiteralList items = (LiteralList) node.rvalue;
                        values = new Object[items.size()];
                        int i = 0;
                        for (Literal item : items) {
                            values[i++] = item.asString();
                        }
                    }
                    // add expression to the last builder
                    EsHint hint = (ref != null) ? ref.esHint : null;
                    builders.getLast()
                            .add(makeQueryFromSimpleExpression(op.toString(), name, value, values, hint, session));
                }
            }
        });

        SearchOperator searchOperator = ret.get();
        if (!fromList.isEmpty()) {
            return SearchOperator.compound().must(List.of(searchOperator)).filter(List.of(
                    makeQueryFromSimpleExpression("IN", NXQL.ECM_PRIMARYTYPE, null,
                            fromList.toArray(), null, null).filter));
        }
        return searchOperator;
    }

    public static SQLQuery getSqlQuery(String nxql) {
        String query = completeQueryWithSelect(nxql);
        SQLQuery nxqlQuery;
        try {
            nxqlQuery = SQLQueryParser.parse(new StringReader(query));
        } catch (QueryParseException e) {
            e.addInfo("Query: " + query);
            throw e;
        }
        return nxqlQuery;
    }

    public static SQLQuery addSecurityPolicy(CoreSession session, SQLQuery query) {
        Collection<SQLQuery.Transformer> transformers = Framework.getService(SecurityService.class)
                .getPoliciesQueryTransformers(
                        session.getRepositoryName());
        for (SQLQuery.Transformer trans : transformers) {
            query = trans.transform(session.getPrincipal(), query);
        }
        return query;
    }

    public static String completeQueryWithSelect(String nxql) {
        String query = (nxql == null) ? "" : nxql.trim();
        if (query.isEmpty()) {
            query = SELECT_ALL;
        } else if (!query.toLowerCase().startsWith("select ")) {
            query = SELECT_ALL_WHERE + nxql;
        }
        return query;
    }

    public static QueryAndFilter makeQueryFromSimpleExpression(String op, String nxqlName, Object value,
                                                               Object[] values, EsHint hint, CoreSession session) {
        SearchOperator query = null;
        SearchOperator filter = null;

        String name = getFieldName(nxqlName, hint);
        /*if (hint != null && hint.operator != null) {
            if (ArrayUtils.isNotEmpty(values)) {
                filter = makeHintQuery(name, values, hint);
            } else {
                query = makeHintQuery(name, value, hint);
            }
        } else*/
        if (nxqlName.startsWith(NXQL.ECM_FULLTEXT) && ("=".equals(op) || "!=".equals(op) || "<>".equals(op)
                || "LIKE".equals(op) || "NOT LIKE".equals(op))) {
            query = makeFulltextQuery(nxqlName, (String) value, hint);
            if ("!=".equals(op) || "<>".equals(op) || "NOT LIKE".equals(op)) {
                filter = SearchOperator.compound().mustNot(List.of(query));
                query = null;
            }
        } else if (nxqlName.startsWith(NXQL.ECM_ANCESTORID)) {
            filter = SearchOperator.of(new Document("equals", new Document("path", KEY_ANCESTOR_IDS).append("value", value)));
            if ("!=".equals(op) || "<>".equals(op)) {
                filter = SearchOperator.compound().mustNot(List.of(filter));
            }
        } else if (isEcmBooleanProperty(nxqlName)) {
            filter = makeBooleanEcmPropertyFilter(op, nxqlName, value);
        } else if (nxqlName.equals(NXQL.ECM_MIXINTYPE)) {
            List<String> mixinTypes = new ArrayList<>();
            if( values == null) {
                mixinTypes.add((String) value);
            } else {
                Arrays.stream(values).forEach(val -> mixinTypes.add((String)val));
            }
            filter = makeMixinTypesFilter(op,name, mixinTypes);
        } else
            switch (op) {
                case "=":
                    filter = SearchOperator.of(new Document("equals", new Document("path", name).append("value", checkBoolValue(nxqlName, value))));
                    break;
                case "<>":
                case "!=":
                    filter = SearchOperator.compound().mustNot(
                            List.of(SearchOperator.of(new Document("equals", new Document("path", name).append("value", checkBoolValue(nxqlName, value))))));
                    break;
                case ">":
                    filter = SearchOperator.of(new Document("range", new Document("path", name).append("gt", value)));
                    break;
                case "<":
                    filter = SearchOperator.of(new Document("range", new Document("path", name).append("lt", value)));
                    break;
                case ">=":
                    filter = SearchOperator.of(new Document("range", new Document("path", name).append("gte", value)));
                    break;
                case "<=":
                    filter = SearchOperator.of(new Document("range", new Document("path", name).append("lte", value)));
                    break;
                case "BETWEEN":
                case "NOT BETWEEN":
                    filter = SearchOperator.of(new Document("range", new Document("path", name).append("gte", values[0]).append("lte", values[1])));
                    if (op.startsWith("NOT")) {
                        filter = SearchOperator.compound().mustNot(List.of(filter));
                    }
                    break;
                case "IN":
                case "NOT IN":
                    filter =  SearchOperator.of(new Document("in",  new Document("path", name).append("value", Arrays.stream(values).toList())));
                    if (op.startsWith("NOT")) {
                        filter = SearchOperator.compound().mustNot(List.of(filter));
                    }
                    break;
                case "IS NULL":
                    filter = SearchOperator.compound().mustNot(List.of(SearchOperator.exists(SearchPath.fieldPath(name))));
                    break;
                case "IS NOT NULL":
                    filter = SearchOperator.exists(SearchPath.fieldPath(name));
                    break;
                /*case "LIKE":
                case "ILIKE":
                case "NOT LIKE":
                case "NOT ILIKE":
                    query = makeLikeQuery(op, name, (String) value, hint);
                    if (op.startsWith("NOT")) {
                        filter = QueryBuilders.boolQuery().mustNot(query);
                        query = null;
                    }
                    break;
                case "STARTSWITH":
                    filter = makeStartsWithQuery(name, value);
                    break;*/
                default:
                    throw new UnsupportedOperationException("Operator: '" + op + "' is unknown");
            }
        return new QueryAndFilter(query, filter);
    }

    public static String getFieldName(String name, EsHint hint) {
        if (hint != null && hint.index != null) {
            return hint.index;
        }

        if (NXQL.ECM_ISVERSION_OLD.equals(name)) {
            name = NXQL.ECM_ISVERSION;
        } else if (NXQL.ECM_UUID.equals(name)) {
            name = KEY_ID;
        } else if (NXQL.ECM_MIXINTYPE.equals(name)) {
            name = KEY_MIXIN_TYPES;
        } else if (NXQL.ECM_TAG.equals(name)) {
            name = "nxtag:tags.label";
        } else if (name.startsWith("file:content")) {
            name = name.substring("file:".length());
        } else if (name.startsWith("picture:")) {
            name = name.substring("picture:".length());
        }

        // complex field
        name = name.replace("/*", "");
        name = name.replace("/", ".");
        return name;
    }

    public static Set<String> getMixinDocumentTypes(String mixin) {
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        Set<String> types = schemaManager.getDocumentTypeNamesForFacet(mixin);
        return types == null ? Collections.emptySet() : types;
    }

    public static List<String> getDocumentTypes() {
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        List<String> documentTypes = new ArrayList<>();
        for (DocumentType docType : schemaManager.getDocumentTypes()) {
            documentTypes.add(docType.getName());
        }
        return documentTypes;
    }

    public static boolean isNeverPerInstanceMixin(String mixin) {
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        return schemaManager.getNoPerDocumentQueryFacets().contains(mixin);
    }

    public static SearchOperator makeMixinTypesFilter(String op, String name, List<String> mixins) {
        boolean include = !List.of("NOT IN", "!=" ,"<>").contains(op);
        /*
         * Primary types that match.
         */
        Set<String> matchPrimaryTypes;
        if (include) {
            matchPrimaryTypes = new HashSet<>();
            for (String mixin : mixins) {
                matchPrimaryTypes.addAll(getMixinDocumentTypes(mixin));
            }
        } else {
            matchPrimaryTypes = new HashSet<>(getDocumentTypes());
            for (String mixin : mixins) {
                matchPrimaryTypes.removeAll(getMixinDocumentTypes(mixin));
            }
        }

        SearchOperator typeFilter =  !matchPrimaryTypes.isEmpty() ?
                SearchOperator.of(new Document("in",  new Document("path", KEY_PRIMARY_TYPE).append("value", matchPrimaryTypes)))
                : null;

        /*
         * Instance mixins that match.
         */
        Set<String> matchMixinTypes = new HashSet<>();
        for (String mixin : mixins) {
            if (!isNeverPerInstanceMixin(mixin)) {
                matchMixinTypes.add(mixin);
            }
        }

        SearchOperator mixinFilter = !matchMixinTypes.isEmpty() ?
                SearchOperator.of(new Document("in",  new Document("path", KEY_MIXIN_TYPES).append("value", matchMixinTypes)))
                :null;

        SearchOperator compoundFilter = null;
        if (include) {
            List<SearchOperator> effectiveList = Stream.of(typeFilter,mixinFilter).filter(Objects::nonNull).toList();
            if (!effectiveList.isEmpty()) {
                compoundFilter = SearchOperator.compound().should(effectiveList).minimumShouldMatch(1);
            }
        } else {
            Document compound = new Document();
            if (typeFilter != null) {
                compound = compound.append("must",List.of(typeFilter.toBsonDocument()));
            }
            if (mixinFilter != null) {
                compound = compound.append("mustNot",List.of(mixinFilter.toBsonDocument()));
            }
            compoundFilter = SearchOperator.of(new Document("compound", compound));
        }
        return compoundFilter;
    }


    public static SearchOperator makeFulltextQuery(String nxqlName, String value, EsHint hint) {
        return SearchOperator.text(SearchPath.wildcardPath("*"), value);
    }

    public static boolean isEcmBooleanProperty(String nxqlName) {
        return switch (nxqlName) {
            case NXQL.ECM_ISPROXY, NXQL.ECM_ISCHECKEDIN, NXQL.ECM_ISTRASHED, NXQL.ECM_ISVERSION, NXQL.ECM_ISVERSION_OLD,
                 NXQL.ECM_ISRECORD, NXQL.ECM_ISFLEXIBLERECORD, NXQL.ECM_HASLEGALHOLD, NXQL.ECM_ISLATESTMAJORVERSION,
                 NXQL.ECM_ISLATESTVERSION -> true;
            default -> false;
        };
    }

    public static SearchOperator makeBooleanEcmPropertyFilter(String op, String name, Object value) {
        boolean equalsTrue = switch (op) {
            case "=" -> true;
            case "<>", "!=" -> false;
            default -> throw new IllegalArgumentException(name + " requires = or <> operator");
        };

        //invert if value is false
        if (!(Boolean)checkBoolValue(name, value)) {
            equalsTrue = !equalsTrue;
        }

        SearchOperator searchOperator = SearchOperator.of(new Document("equals", new Document("path", name).append("value", true)));

        //field may not exist, be null or contain false so the only reliable filter is "not equal to true"
        if (!equalsTrue) {
            searchOperator = SearchOperator.compound().mustNot(List.of(searchOperator));
        }

        return searchOperator;
    }

    public static Object checkBoolValue(String nxqlName, Object value) {
        // is the nxql field a boolean?
        boolean isBoolean = false;

        if (isEcmBooleanProperty(nxqlName)) {
            isBoolean = true;
        } else {
            SchemaManager schemaManager = Framework.getService(SchemaManager.class);
            Field field = schemaManager.getField(nxqlName);
            if (field != null  && BooleanType.ID.equals(field.getType().getName())) {
                isBoolean = true;
            }
        }

        if (!isBoolean) {
            //nothing to do because the target field is not a boolean
            return value;
        }

        if ( value instanceof Boolean) {
            return value;
        } else if (value instanceof Long){
            return (Long)value == 1;
        } else if (value instanceof String){
            return "1".equals(value) || "true".equals(value);
        } else {
            throw new NuxeoException(String.format( "Invalid boolean value %s:%s",nxqlName,value.toString()));
        }
    }

    /**
     * Class to hold both a query and a filter
     */
    public record QueryAndFilter(SearchOperator query, SearchOperator filter) {}

    public static class ExpressionBuilder {

        public final String operator;

        public CompoundSearchOperator query;

        public ExpressionBuilder(final String op) {
            this.operator = op;
            this.query = null;
        }

        public void add(final QueryAndFilter qf) {
            if (qf != null) {
                add(qf.query, qf.filter);
            }
        }

        public void add(SearchOperator q) {
            add(q, null);
        }

        public void add(final SearchOperator q, final SearchOperator f) {
            if (q == null && f == null) {
                return;
            }
            SearchOperator inputQuery = q;
            if (inputQuery == null) {
                inputQuery = f;
            }
            if (operator == null) {
                // first level expression
                query = (CompoundSearchOperator) inputQuery;
            } else {
                // boolean query
                switch (operator) {
                    case "AND" ->
                            query = query != null ? query.must(List.of(inputQuery)) :
                                    SearchOperator.compound().must(List.of(inputQuery));
                    case "OR" ->
                            query = query != null ? query.should(List.of(inputQuery)) :
                                    SearchOperator.compound().should(List.of(inputQuery));
                    case "NOT" ->
                            query = query != null ? query.mustNot(List.of(inputQuery)) :
                                    SearchOperator.compound().mustNot(List.of(inputQuery));
                }
            }
        }

        public void merge(ExpressionBuilder expr) {
            if ((expr.operator != null) && expr.operator.equals(operator) && (query == null)) {
                query = expr.query;
            } else {
                add(new QueryAndFilter(expr.query, null));
            }
        }

        public SearchOperator get() {
            return query;
        }

        @Override
        public String toString() {
            return query.toString();
        }

    }

}
