# Description
A plugin that provides a PageProvider implementation for [MongoDB Atlas Search](https://www.mongodb.com/docs/atlas/atlas-search/) for the Nuxeo Platform.

# How to build
```bash
git clone https://github.com/nuxeo-sandbox/nuxeo-mongodb-atlas-search
cd nuxeo-mongodb-atlas-search
mvn clean install -DskipTests
```

# How to configure
## MongoDB Atlas
In MongoDB Atlas, create a search index `default` for the collection `default` and use the [sample mapping](/atlas/default_index_mapping.json) as a starting point.

## Nuxeo
To use the atlas search implementation, simply configure the PageProvider to use the atlas search implementation class

```xml
<extension point="providers" target="org.nuxeo.ecm.platform.query.api.PageProviderService">
    <genericPageProvider class="org.nuxeo.labs.atlas.search.pp.MongoDBAtlasSearchPP" name="my_pp">
    </genericPageProvider>
</extension>
```

# Limitations
## NXQL
The current implementation doesn't support the following NXQL operator:
- STARTSWITH
- LIKE / NOT LIKE
- ILIKE / NOT ILIKE

## Facet
[Altas Search facet aggregation capabilities](https://www.mongodb.com/docs/atlas/atlas-search/facet/) are much more limited than Elasticsearch/OpenSearch. Only the following types are supported:
- terms
- range
- date_range

Moreover, the following limitations applies to `range` and `date_range`:
- cannot be used on complex properties subfields
- ranges are not independent and must be contiguous

# Support
**These features are not part of the Nuxeo Production platform.**

These solutions are provided for inspiration and we encourage customers to use them as code samples and learning
resources.

This is a moving project (no API maintenance, no deprecation process, etc.) If any of these solutions are found to be
useful for the Nuxeo Platform in general, they will be integrated directly into platform, not maintained here.

# Nuxeo Marketplace
TODO

The marketplace package contains a configuration template `nuxeo-mongodb-atlas-search` which, when installed, makes the following PageProvider use Atlas Search:
- default_search
- default_trash_search
- simple_search
- expired_search

# License
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)

# About Nuxeo
Nuxeo Platform is an open source Content Services platform, written in Java. Data can be stored in both SQL & NoSQL
databases.

The development of the Nuxeo Platform is mostly done by Nuxeo employees with an open development model.

The source code, documentation, roadmap, issue tracker, testing, benchmarks are all public.

Typically, Nuxeo users build different types of information management solutions
for [document management](https://www.nuxeo.com/solutions/document-management/), [case management](https://www.nuxeo.com/solutions/case-management/),
and [digital asset management](https://www.nuxeo.com/solutions/dam-digital-asset-management/), use cases. It uses
schema-flexible metadata & content models that allows content to be repurposed to fulfill future use cases.

More information is available at [www.nuxeo.com](https://www.nuxeo.com).