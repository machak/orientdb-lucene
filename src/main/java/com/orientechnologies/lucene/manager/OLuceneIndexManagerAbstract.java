/*
 * Copyright 2014 Orient Technologies.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientechnologies.lucene.manager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.TrackingIndexWriter;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;

import com.orientechnologies.common.concur.resource.OSharedResourceAdaptiveExternal;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.lucene.OLuceneIndexType;
import com.orientechnologies.lucene.OLuceneMapEntryIterator;
import com.orientechnologies.lucene.query.QueryContext;
import com.orientechnologies.lucene.utils.OLuceneIndexUtils;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.id.OContextualRecordId;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.index.OIndexCursor;
import com.orientechnologies.orient.core.index.OIndexDefinition;
import com.orientechnologies.orient.core.index.OIndexEngine;
import com.orientechnologies.orient.core.index.OIndexException;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.stream.OStreamSerializer;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;

public abstract class OLuceneIndexManagerAbstract<V> extends OSharedResourceAdaptiveExternal implements OIndexEngine<V> {

  public static final String               RID              = "RID";
  public static final String               KEY              = "KEY";
  public static final String               STORED           = "_STORED";
  public static final Version              LUCENE_VERSION   = Version.LUCENE_5_2_0;

  public static final String               OLUCENE_BASE_DIR = "luceneIndexes";

  protected SearcherManager                searcherManager;
  protected OIndexDefinition               index;
  protected TrackingIndexWriter            mgrWriter;
  protected String                         indexName;
  protected String                         clusterIndexName;
  protected OStreamSerializer              serializer;
  protected boolean                        automatic;
  protected ControlledRealTimeReopenThread nrt;
  protected ODocument                      metadata;
  protected Version                        version;
  private OIndex                           managedIndex;
  private boolean                          rebuilding;
  private long                             reopenToken;
  protected Map<String, Boolean>           collectionFields = new HashMap<String, Boolean>();

  public OLuceneIndexManagerAbstract() {
    super(OGlobalConfiguration.ENVIRONMENT_CONCURRENT.getValueAsBoolean(), OGlobalConfiguration.MVRBTREE_TIMEOUT
        .getValueAsInteger(), true);
  }


    @Override
    public void create(OIndexDefinition indexDefinition, String clusterIndexName, OStreamSerializer valueSerializer, boolean isAutomatic) {
          createIndex(clusterIndexName, indexDefinition, clusterIndexName, valueSerializer, isAutomatic, metadata);
    }

  public void createIndex(String indexName, OIndexDefinition indexDefinition, String clusterIndexName,
      OStreamSerializer valueSerializer, boolean isAutomatic, ODocument metadata) {
    // PREVENT EXCEPTION
    if (mgrWriter == null)
      initIndex(indexName, indexDefinition, clusterIndexName, valueSerializer, isAutomatic, metadata);
  }

  public abstract IndexWriter openIndexWriter(Directory directory, ODocument metadata) throws IOException;

  public abstract IndexWriter createIndexWriter(Directory directory, ODocument metadata) throws IOException;

  public void addDocument(Document doc) {
    try {

      reopenToken = mgrWriter.addDocument(doc);
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on adding new document '%s' to Lucene index", e, doc);
    }
  }

  public void deleteDocument(Query query) {
    try {
      reopenToken = mgrWriter.deleteDocuments(query);
      if (!mgrWriter.getIndexWriter().hasDeletions()) {
        OLogManager.instance().error(this, "Error on deleting document by query '%s' to Lucene index",
            new OIndexException("Error deleting document"), query);
      }
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on deleting document by query '%s' to Lucene index", e, query);
    }
  }

  public boolean remove(Object key, OIdentifiable value) {

    Query query = null;
    if (isCollectionDelete()) {
      query = OLuceneIndexType.createDeleteQuery(value, index.getFields(), key);
    } else {
      query = OLuceneIndexType.createQueryId(value);
    }
    if (query != null)
      deleteDocument(query);
    return true;
  }

  public void commit() {
    try {
      mgrWriter.getIndexWriter().commit();
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on committing Lucene index", e);
    }
  }

  public void delete() {
    try {
      if (mgrWriter.getIndexWriter() != null) {
        closeIndex();
      }
      ODatabaseDocumentInternal database = getDatabase();
      final OAbstractPaginatedStorage storageLocalAbstract = (OAbstractPaginatedStorage) database.getStorage().getUnderlying();
      if (storageLocalAbstract instanceof OLocalPaginatedStorage) {

        File f = new File(getIndexPath((OLocalPaginatedStorage) storageLocalAbstract));
        OLuceneIndexUtils.deleteFolder(f);
        f = new File(getIndexBasePath((OLocalPaginatedStorage) storageLocalAbstract));
        OLuceneIndexUtils.deleteFolderIfEmpty(f);
      }
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on deleting Lucene index", e);
    }
  }

  public Iterator<Map.Entry<Object, V>> iterator() {
    try {
      IndexReader reader = getSearcher().getIndexReader();
      return new OLuceneMapEntryIterator<>(reader, index);

    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on creating iterator against Lucene index", e);
    }
    return Collections.emptyIterator();
  }

  public void clear() {
    try {
      mgrWriter.getIndexWriter().deleteAll();
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on clearing Lucene index", e);
    }
  }

  @Override
  public void flush() {

    try {
      mgrWriter.getIndexWriter().commit();
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on flushing Lucene index", e);
    } catch (Throwable e) {
      e.printStackTrace();
    }

  }

  @Override
  public void close() {

    try {
      closeIndex();
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on closing Lucene index", e);
    } catch (Throwable e) {
        OLogManager.instance().error(this, "Error on closing Lucene index", e);
    }
  }

  public void rollback() {
    try {
      mgrWriter.getIndexWriter().rollback();
      reOpen(metadata);
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on rolling back Lucene index", e);
    }
  }

  @Override
  public void load(ORID indexRid, String indexName, OIndexDefinition indexDefinition, OStreamSerializer valueSerializer,
      boolean isAutomatic) {
      OLogManager.instance().error(this, "calling load index");
  }

  public void load(ORID indexRid, String indexName, OIndexDefinition indexDefinition, boolean isAutomatic, ODocument metadata) {
    initIndex(indexName, indexDefinition, null, null, isAutomatic, metadata);
  }

  public long size(ValuesTransformer<V> transformer) {

    IndexReader reader = null;
    IndexSearcher searcher = null;
    try {
        searcher = getSearcher();
        reader = getSearcher().getIndexReader();
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on getting size of Lucene index", e);
    } finally {
      if (searcher != null) {
        release(searcher);
      }
    }
      if (reader == null) {
          return 0;
      }
    return reader.numDocs();
  }

  public void release(IndexSearcher searcher) {
    try {
      searcherManager.release(searcher);
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on releasing index searcher  of Lucene index", e);
    }
  }

  public void setManagedIndex(OIndex index) {
    this.managedIndex = index;
  }

  public boolean isRebuilding() {
    return rebuilding;
  }

  public void setRebuilding(boolean rebuilding) {
    this.rebuilding = rebuilding;
  }

  public Analyzer getAnalyzer(final ODocument metadata) {
    Analyzer analyzer = null;
    if (metadata != null && metadata.field("analyzer") != null) {
      final String analyzerString = metadata.field("analyzer");
      if (analyzerString != null) {
        try {

          final Class classAnalyzer = Class.forName(analyzerString);
          final Constructor constructor = classAnalyzer.getConstructor(Version.class);

          analyzer = (Analyzer) constructor.newInstance(getLuceneVersion(metadata));
        } catch (ClassNotFoundException e) {
          throw new OIndexException("Analyzer: " + analyzerString + " not found", e);
        } catch (NoSuchMethodException e) {
          Class classAnalyzer = null;
          try {
            classAnalyzer = Class.forName(analyzerString);
            analyzer = (Analyzer) classAnalyzer.newInstance();

          } catch (Throwable e1) {
            throw new OIndexException("Couldn't instantiate analyzer:  public constructor  not found", e1);
          }

        } catch (Exception e) {
          OLogManager.instance().error(this, "Error on getting analyzer for Lucene index", e);
        }
      }
    } else {
      analyzer = new StandardAnalyzer();
    }
    return analyzer;
  }

  public Version getLuceneVersion(ODocument metadata) {
    if (version == null) {
      version = LUCENE_VERSION;
    }
    return version;
  }

  protected void initIndex(String indexName, OIndexDefinition indexDefinition, String clusterIndexName,
      OStreamSerializer valueSerializer, boolean isAutomatic, ODocument metadata) {
    this.index = indexDefinition;
    this.indexName = indexName;
    this.serializer = valueSerializer;
    this.automatic = isAutomatic;
    this.clusterIndexName = clusterIndexName;
    this.metadata = metadata;
    try {

      this.index = indexDefinition;

      checkCollectionIndex(indexDefinition);
      reOpen(metadata);

    } catch (IOException e) {
      OLogManager.instance().error(this, "Error on initializing Lucene index", e);
    }
  }

  private void checkCollectionIndex(OIndexDefinition indexDefinition) {

    List<String> fields = indexDefinition.getFields();

    OClass aClass = getDatabase().getMetadata().getSchema().getClass(indexDefinition.getClassName());
    for (String field : fields) {
      OProperty property = aClass.getProperty(field);

      if (property.getType().isEmbedded() && property.getLinkedType() != null) {
        collectionFields.put(field, true);
      } else {
        collectionFields.put(field, false);
      }
    }
  }

  protected Field.Store isToStore(String f) {
    return collectionFields.get(f) ? Field.Store.YES : Field.Store.NO;
  }

  protected boolean isCollectionDelete() {
    boolean collectionDelete = false;
    for (Boolean aBoolean : collectionFields.values()) {
      collectionDelete = collectionDelete || aBoolean;
    }
    return collectionDelete;
  }

  public IndexSearcher getSearcher() throws IOException {
    try {
      nrt.waitForGeneration(reopenToken);
    } catch (InterruptedException e) {
      OLogManager.instance().error(this, "Error on get searcher from Lucene index", e);
    }
    return searcherManager.acquire();
  }

  protected void closeIndex() throws IOException {
    nrt.interrupt();
    nrt.close();
    searcherManager.close();
    mgrWriter.getIndexWriter().commit();
    mgrWriter.getIndexWriter().close();
  }

  private void reOpen(ODocument metadata) throws IOException {
    ODatabaseDocumentInternal database = getDatabase();

    final OAbstractPaginatedStorage storageLocalAbstract = (OAbstractPaginatedStorage) database.getStorage().getUnderlying();
    Directory dir;
    if (storageLocalAbstract instanceof OLocalPaginatedStorage) {
      String pathname = getIndexPath((OLocalPaginatedStorage) storageLocalAbstract);
      dir = NIOFSDirectory.open(new File(pathname).toPath());
    } else {
      dir = new RAMDirectory();
    }
    IndexWriter indexWriter = createIndexWriter(dir, metadata);
    mgrWriter = new TrackingIndexWriter(indexWriter);
    searcherManager = new SearcherManager(indexWriter, true, null);
    if (nrt != null) {
      nrt.close();
    }

    nrt = new ControlledRealTimeReopenThread(mgrWriter, searcherManager, 60.00, 0.1);
    nrt.setDaemon(true);
    nrt.start();
    flush();
  }

  public void sendTotalHits(OCommandContext context, TopDocs docs) {
    if (context != null) {

      if (context.getVariable("totalHits") == null) {
        context.setVariable("totalHits", docs.totalHits);
      } else {
        context.setVariable("totalHits", null);
      }
      context.setVariable((indexName + ".totalHits").replace(".", "_"), docs.totalHits);
    }
  }

  public void sendLookupTime(OCommandContext context, final TopDocs docs, final Integer limit, long startFetching) {
    if (context != null) {

      final long finalTime = System.currentTimeMillis() - startFetching;
      context.setVariable((indexName + ".lookupTime").replace(".", "_"), new HashMap<String, Object>() {
        {
          put("limit", limit);
          put("totalTime", finalTime);
          put("totalHits", docs.totalHits);
          put("returnedHits", docs.scoreDocs.length);
          put("maxScore", docs.getMaxScore());

        }
      });
    }
  }

  private String getIndexPath(OLocalPaginatedStorage storageLocalAbstract) {
    return storageLocalAbstract.getStoragePath() + File.separator + OLUCENE_BASE_DIR + File.separator + indexName;
  }

  protected String getIndexBasePath(OLocalPaginatedStorage storageLocalAbstract) {
    return storageLocalAbstract.getStoragePath() + File.separator + OLUCENE_BASE_DIR;
  }

  protected ODatabaseDocumentInternal getDatabase() {
    return ODatabaseRecordThreadLocal.INSTANCE.get();
  }

  @Override
  public OIndexCursor descCursor(ValuesTransformer<V> vValuesTransformer) {
    return null;
  }

  public abstract void onRecordAddedToResultSet(QueryContext queryContext, OContextualRecordId recordId, Document ret,
      ScoreDoc score);

  @Override
  public int getVersion() {
    return 0;
  }
}
