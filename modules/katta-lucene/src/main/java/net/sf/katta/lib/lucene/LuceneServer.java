/**
 * Copyright 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.katta.lib.lucene;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.sf.katta.lib.lucene.query.ILuceneQueryAndFilterWritable;
import net.sf.katta.node.IContentServer;
import net.sf.katta.util.ClassUtil;
import net.sf.katta.util.NodeConfiguration;
import net.sf.katta.util.WritableType;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.CachingWrapperFilter;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TimeLimitingCollector;
import org.apache.lucene.search.TimeLimitingCollector.TimeExceededException;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Counter;
import org.apache.lucene.util.PriorityQueue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The back end server which searches a set of Lucene indices. Each shard is a
 * Lucene index directory.
 * <p>
 * Normal usage is to first call getDocFreqs() to get the global term
 * frequencies, then pass that back in to search(). This way you get uniform
 * scoring across all the nodes / instances of LuceneServer.
 */
public class LuceneServer implements IContentServer, ILuceneServer {

  private final static Logger LOG = Logger.getLogger(LuceneServer.class);
  public final static String CONF_KEY_SEARCHER_FACTORY_CLASS = "lucene.searcher.factory-class";
  public final static String CONF_KEY_COLLECTOR_TIMOUT_PERCENTAGE = "lucene.collector.timeout-percentage";
  public final static String CONF_KEY_SEARCHER_THREADPOOL_CORESIZE = "lucene.searcher.threadpool.core-size";
  public final static String CONF_KEY_SEARCHER_THREADPOOL_MAXSIZE = "lucene.searcher.threadpool.max-size";
  public final static String CONF_KEY_FILTER_CACHE_ENABLED = "lucene.filter.cache.enabled";



  protected final Map<String, IndexHandle> _indexHandlesByShard = new ConcurrentHashMap<String, IndexHandle>();
  protected Cache<Filter, CachingWrapperFilter> _filterCache;
  protected ExecutorService _threadPool;
  private TimeLimitingCollector.TimerThread _searchTimerThread;
  private Counter _searchTimerCounter;

  protected String _nodeName;
  private float _timeoutPercentage = 0.75f;
  private ISeacherFactory _seacherFactory;

  public LuceneServer() {
    // default way of initializing an IContentServer
  }

  /**
   * Constructor for testing purpose, {@link #init(String, NodeConfiguration)}
   * need not to be called.
   * 
   * @param name
   * @param seacherFactory
   * @param timeoutPercentage
   */
  public LuceneServer(String name, ISeacherFactory seacherFactory, float timeoutPercentage) {
    Properties properties = new Properties();
    init(name, new NodeConfiguration(properties));
    _seacherFactory = seacherFactory;
    _timeoutPercentage = timeoutPercentage;
  }

  @Override
  public long getProtocolVersion(final String protocol, final long clientVersion) throws IOException {
    return 0L;
  }

  @Override
  public void init(String nodeName, NodeConfiguration nodeConfiguration) {
    _nodeName = nodeName;
    _seacherFactory = (ISeacherFactory) ClassUtil.newInstance(nodeConfiguration.getClass(
            CONF_KEY_SEARCHER_FACTORY_CLASS, DefaultSearcherFactory.class));
    _timeoutPercentage = nodeConfiguration.getFloat(CONF_KEY_COLLECTOR_TIMOUT_PERCENTAGE, _timeoutPercentage);
    if (_timeoutPercentage < 0 || _timeoutPercentage > 1) {
      throw new IllegalArgumentException("illegal value '" + _timeoutPercentage + "' for "
              + CONF_KEY_COLLECTOR_TIMOUT_PERCENTAGE + ". Only values between 0 and 1 are allowed.");
    }
    int coreSize = nodeConfiguration.getInt(CONF_KEY_SEARCHER_THREADPOOL_CORESIZE, 25);
    int maxSize = nodeConfiguration.getInt(CONF_KEY_SEARCHER_THREADPOOL_MAXSIZE, 100);
    boolean filterCacheEnabled = nodeConfiguration.getBoolean(CONF_KEY_FILTER_CACHE_ENABLED, true);
    _threadPool = new ThreadPoolExecutor(coreSize, maxSize, 100L, TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>());
    if (filterCacheEnabled) {
      _filterCache = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).maximumSize(1000).build();
    }

    _searchTimerCounter = Counter.newCounter(true);
    _searchTimerThread = new TimeLimitingCollector.TimerThread(_searchTimerCounter);
    _searchTimerThread.start();
  }

  public String getNodeName() {
    return _nodeName;
  }

  public float getTimeoutPercentage() {
    return _timeoutPercentage;
  }

  public long getCollectorTiemout(long clientTimeout) {
    return (long) (_timeoutPercentage * clientTimeout);
  }

  /**
   * Adds an shard index search for given name to the list of shards
   * MultiSearcher search in.
   * 
   * @param shardName
   * @param shardDir
   * @throws IOException
   */
  @Override
  public void addShard(final String shardName, final File shardDir) throws IOException {
    LOG.info("LuceneServer " + _nodeName + " got shard " + shardName);
    try {
      IndexHandle indexHandle = _seacherFactory.createSearcher(shardName, shardDir);
      _indexHandlesByShard.put(shardName, indexHandle);
    } catch (CorruptIndexException e) {
      LOG.error("Error building index for shard " + shardName, e);
      throw e;
    }
  }

  /**
   * Removes a search by given shardName from the list of searchers.
   */
  @Override
  public void removeShard(final String shardName) {
    LOG.info("LuceneServer " + _nodeName + " removing shard " + shardName);
    IndexHandle handle = _indexHandlesByShard.remove(shardName);

    if (handle == null) {
      return; // nothing to do.
    }
    try {
      _seacherFactory.closeSearcher(handle);
    } catch (Exception e) {
      LOG.error("LuceneServer " + _nodeName + " error removing shard " + shardName, e);
    }
  }

  @Override
  public Collection<String> getShards() {
    return Collections.unmodifiableCollection(_indexHandlesByShard.keySet());
  }

  /**
   * Returns the number of documents a shard has.
   * 
   * @param shardName
   * @return the number of documents in the shard.
   */
  protected int shardSize(String shardName) {
    final IndexHandle handle = getSearcherHandleByShard(shardName);
    IndexSearcher searcher = handle.getSearcher();
    try {
      if (searcher != null) {
        int size = searcher.getIndexReader().numDocs();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Shard '" + shardName + "' has " + size + " docs.");
        }
        return size;
      }
      throw new IllegalArgumentException("Shard '" + shardName + "' unknown");
    } finally {
      handle.finishSearcher();
    }
  }

  /**
   * Returns data about a shard. Currently the only standard key is
   * SHARD_SIZE_KEY. This value will be reported by the listIndexes command. The
   * units depend on the type of server. It is OK to return an empty map or
   * null.
   * 
   * @param shardName
   *          The name of the shard to measure. This was the name provided in
   *          addShard().
   * @return a map of key/value pairs which describe the shard.
   * @throws Exception
   */
  @Override
  public Map<String, String> getShardMetaData(String shardName) throws Exception {
    Map<String, String> metaData = new HashMap<String, String>();
    metaData.put(SHARD_SIZE_KEY, Integer.toString(shardSize(shardName)));
    return metaData;
  }

  /**
   * Close all Lucene indices. No further calls will be made after this one.
   */
  @Override
  public void shutdown() throws IOException {
    for (final IndexHandle handle : _indexHandlesByShard.values()) {
      handle.closeSearcher();
    }
    _indexHandlesByShard.clear();
    _searchTimerThread.stopTimer();
  }

  /**
   * Returns the <code>IndexHandle</code> of the given shardName.
   * 
   * @param shardName
   *          the name of the shard
   * @return the <code>IndexHandle</code> of the given shardName
   */
  protected IndexHandle getSearcherHandleByShard(String shardName) {
    IndexHandle handle = _indexHandlesByShard.get(shardName);
    if (handle == null) {
      throw new IllegalStateException("no index-server for shard '" + shardName + "' found - probably undeployed");
    }
    return handle;
  }

  @Override
  public HitsMapWritable search(ILuceneQueryAndFilterWritable queryAndFilter, DocumentFrequencyWritable freqs, String[] shardNames, long timeout)
          throws IOException {
    return search(queryAndFilter, freqs, shardNames, timeout, Integer.MAX_VALUE);
  }

  @Override
  public HitsMapWritable search(final ILuceneQueryAndFilterWritable queryAndFilter, final DocumentFrequencyWritable freqs,
          final String[] shards, final long timeout, final int count) throws IOException {
    return search(queryAndFilter, freqs, shards, timeout, count, null);
  }

  @Override
  public HitsMapWritable search(ILuceneQueryAndFilterWritable query, DocumentFrequencyWritable freqs, String[] shards,
          final long timeout, int count, SortWritable sortWritable) throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("You are searching with the getQuery: '" + query.getQuery() + "'");
    }

    Query luceneQuery = query.getQuery();

    if (LOG.isDebugEnabled()) {
      LOG.debug("Lucene getQuery: " + luceneQuery.toString());
    }

    long completeSearchTime = 0;
    final HitsMapWritable result = new HitsMapWritable(getNodeName());
    long start = 0;
    if (LOG.isDebugEnabled()) {
      start = System.currentTimeMillis();
    }
    Sort sort = null;
    if (sortWritable != null) {
      sort = sortWritable.getSort();
    }
    Filter filter = query.getFilter();
    if (_filterCache != null && filter != null) {
      CachingWrapperFilter cachedFilter = _filterCache.getIfPresent(filter);
      if (cachedFilter == null) {
        cachedFilter = new CachingWrapperFilter(filter);
        _filterCache.put(filter, cachedFilter);
      }
      filter = cachedFilter;
    }
    search(luceneQuery, freqs, shards, result, count, sort, timeout, filter);
    if (LOG.isDebugEnabled()) {
      final long end = System.currentTimeMillis();
      LOG.debug("Search took " + (end - start) / 1000.0 + "sec.");
      completeSearchTime += (end - start);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Complete search took " + completeSearchTime / 1000.0 + "sec.");
      final DataOutputBuffer buffer = new DataOutputBuffer();
      result.write(buffer);
      LOG.debug("Result size to transfer: " + buffer.getLength());
    }
    return result;
  }

  /**
   * NOTE: filter is ignored.
   * @param input       TODO is this really just a Lucene getQuery?
   * @param shards      The shards to search in.
   * @return
   * @throws IOException
   */
  @Override
  public DocumentFrequencyWritable getDocFreqs(final ILuceneQueryAndFilterWritable input, final String[] shards) throws IOException {
    Query luceneQuery = input.getQuery();
    final Query rewrittenQuery = rewrite(luceneQuery, shards);
    final DocumentFrequencyWritable docFreqs = new DocumentFrequencyWritable();

    final HashSet<Term> termSet = new HashSet<Term>();
    rewrittenQuery.extractTerms(termSet);
    for (final String shard : shards) {
      final java.util.Iterator<Term> termIterator = termSet.iterator();
      IndexHandle handle = getSearcherHandleByShard(shard);
      IndexReader reader = handle.getSearcher().getIndexReader();
      if (reader != null) {
        try {
          while (termIterator.hasNext()) {
            final Term term = termIterator.next();
            final int docFreq = reader.docFreq(term);
            docFreqs.put(term.field(), term.text(), docFreq);
          }
        } finally {
          handle.finishSearcher();
        }
      }
      docFreqs.addNumDocs(shardSize(shard));
    }
    return docFreqs;
  }

  @Override
  public MapWritable getDetails(final String[] shards, final MapWritable docIdsByShard) throws IOException {
    return getDetails(shards, docIdsByShard, null);
  }

  @Override
  public MapWritable getDetails(final String[] shards, final MapWritable docIdsByShard, final String[] fieldNames) throws IOException {
    final MapWritable result = new MapWritable();
    
    for (String shard : shards) {
      Writable[] docIdsWritable = ((ArrayWritable)docIdsByShard.get(new Text(shard))).get();
      int docIds[] = new int[docIdsWritable.length];
      for (int i = 0; i < docIdsWritable.length; i++) {
        docIds[i] = ((IntWritable) docIdsWritable[i]).get();
      }
      
      final Document[] docs = docs(shard, docIds, fieldNames);
      
      final MapWritable[] shardDocsWritables = new MapWritable[docIdsWritable.length];
      
      int i = 0;
      for (Document doc : docs) {
        final MapWritable docWritable = new MapWritable();
        shardDocsWritables[i++] = docWritable;

        final List<IndexableField> fields = doc.getFields();
        for (final IndexableField field : fields) {
          final String name = field.name();
          // TODO this code used to get the String value for some field types
          final BytesRef binaryValue = field.binaryValue();
          // TODO we should check the type?
          if (binaryValue != null) {
            docWritable.put(new Text(name), new BytesWritable(binaryValue.bytes));
          } else {
            String stringValue = field.stringValue();
            if (stringValue != null) {
              docWritable.put(new Text(name), new Text(stringValue));
            }
          }
        }
      }
      
      result.put(new Text(shard), new MapArrayWritable(shardDocsWritables));
    }
    
    return result;
  }

  @Override
  public int getResultCount(final ILuceneQueryAndFilterWritable queryAndFilter, final String[] shards, long timeout) throws IOException {
    final DocumentFrequencyWritable docFreqs = getDocFreqs(queryAndFilter, shards);
    return search(queryAndFilter, docFreqs, shards, timeout, 1).getTotalHits();
  }


  /**
   * Search in the given shards and return max hits for given query
   * 
   * @param query
   * @param freqs
   * @param shards
   * @param result
   * @param max
   * @throws IOException
   */
  protected final void search(final Query query, final DocumentFrequencyWritable freqs, final String[] shards,
          final HitsMapWritable result, final int max, Sort sort, long timeout, Filter filter) throws IOException {
    timeout = getCollectorTiemout(timeout);
    final Query rewrittenQuery = rewrite(query, shards);
    final int numDocs = freqs.getNumDocsAsInteger();

    // TODO should similarity be passed in as an argument?
    //final Weight weight = rewrittenQuery.createWeight(new CachedDfSource(freqs.getAll(), numDocs, new DefaultSimilarity()));

    int totalHits = 0;
    final int shardsCount = shards.length;

    // Run the search in parallel on the shards with a thread pool.
    CompletionService<SearchResult> csSearch = new ExecutorCompletionService<SearchResult>(_threadPool);

    for (int i = 0; i < shardsCount; i++) {
      SearchCall call = new SearchCall(shards[i], rewrittenQuery, freqs.getAll(), max, sort, timeout, i, filter);
      csSearch.submit(call);
    }

    final ScoreDoc[][] scoreDocs = new ScoreDoc[shardsCount][];
    ScoreDoc scoreDocExample = null;
    for (int i = 0; i < shardsCount; i++) {
      try {
        final SearchResult searchResult = csSearch.take().get();
        final int callIndex = searchResult.getSearchCallIndex();

        totalHits += searchResult._totalHits;
        scoreDocs[callIndex] = searchResult._scoreDocs;
        if (scoreDocExample == null && scoreDocs[callIndex].length > 0) {
          scoreDocExample = scoreDocs[callIndex][0];
        }
      } catch (InterruptedException e) {
        throw new IOException("Multithread shard search interrupted:", e);
      } catch (ExecutionException e) {
        throw new IOException("Multithread shard search could not be executed:", e);
      }
    }

    result.addTotalHits(totalHits);

    final Iterable<Hit> finalHitList;
    // Limit the request to the number requested or the total number of
    // documents, whichever is smaller.
    int limit = Math.min(numDocs, max);
    if (sort == null || totalHits == 0) {
      final KattaHitQueue hq = new KattaHitQueue(limit);
      int pos = 0;
      BitSet done = new BitSet(shardsCount);
      while (done.cardinality() != shardsCount) {
        ScoreDoc scoreDoc = null;
        for (int i = 0; i < shardsCount; i++) {
          // only process this shard if it is not yet done.
          if (!done.get(i)) {
            final ScoreDoc[] docs = scoreDocs[i];
            if (pos < docs.length) {
              scoreDoc = docs[pos];
              final Hit hit = new Hit(shards[i], getNodeName(), scoreDoc.score, scoreDoc.doc);
              if (!hq.insert(hit)) {
                // no doc left that has a higher score than the lowest score in
                // the queue
                done.set(i, true);
              }
            } else {
              // no docs left in this shard
              done.set(i, true);
            }
          }
        }
        // we always wait until we got all hits from this position in all
        // shards.

        pos++;
        if (scoreDoc == null) {
          // we do not have any more data
          break;
        }
      }
      finalHitList = hq;
    } else {
      WritableType[] sortFieldsTypes = null;
      FieldDoc fieldDoc = (FieldDoc) scoreDocExample;
      sortFieldsTypes = WritableType.detectWritableTypes(fieldDoc.fields);
      result.setSortFieldTypes(sortFieldsTypes);
      finalHitList = mergeFieldSort(new FieldSortComparator(sort.getSort(), sortFieldsTypes), limit, scoreDocs, shards,
              getNodeName());
    }

    for (Hit hit : finalHitList) {
      if (hit != null) {
        result.addHit(hit);
      }
    }
  }

  /**
   * Merges the already sorted sub-lists to one big sorted list.
   */
  private final static List<Hit> mergeFieldSort(FieldSortComparator comparator, int count,
          ScoreDoc[][] sortedFieldDocs, String[] shards, String nodeName) {
    int[] arrayPositions = new int[sortedFieldDocs.length];
    final List<Hit> sortedResult = new ArrayList<Hit>(count);

    BitSet listDone = new BitSet(sortedFieldDocs.length);
    for (int subListIndex = 0; subListIndex < arrayPositions.length; subListIndex++) {
      if (sortedFieldDocs[subListIndex].length == 0) {
        listDone.set(subListIndex, true);
      }
    }
    do {
      int fieldDocArrayWithSmallestFieldDoc = -1;
      FieldDoc smallestFieldDoc = null;
      for (int subListIndex = 0; subListIndex < arrayPositions.length; subListIndex++) {
        if (!listDone.get(subListIndex)) {
          FieldDoc hit = (FieldDoc) sortedFieldDocs[subListIndex][arrayPositions[subListIndex]];
          if (smallestFieldDoc == null || comparator.compare(hit.fields, smallestFieldDoc.fields) < 0) {
            smallestFieldDoc = hit;
            fieldDocArrayWithSmallestFieldDoc = subListIndex;
          }
        }
      }
      ScoreDoc[] smallestElementList = sortedFieldDocs[fieldDocArrayWithSmallestFieldDoc];
      FieldDoc fieldDoc = (FieldDoc) smallestElementList[arrayPositions[fieldDocArrayWithSmallestFieldDoc]];
      arrayPositions[fieldDocArrayWithSmallestFieldDoc]++;
      final Hit hit = new Hit(shards[fieldDocArrayWithSmallestFieldDoc], nodeName, fieldDoc.score, fieldDoc.doc);
      hit.setSortFields(WritableType.convertComparable(comparator.getFieldTypes(), fieldDoc.fields));
      sortedResult.add(hit);
      if (arrayPositions[fieldDocArrayWithSmallestFieldDoc] >= smallestElementList.length) {
        listDone.set(fieldDocArrayWithSmallestFieldDoc, true);
      }
    } while (sortedResult.size() < count && listDone.cardinality() < arrayPositions.length);
    return sortedResult;
  }

  /**
   * Returns a specified lucene document from a given shard where all or only
   * the given fields are loaded from the index.
   * 
   * @param shardName
   * @param docId
   * @param fieldNames
   * @return
   * @throws IOException
   */
  protected Document doc(final String shardName, final int docId, final String[] fieldNames) throws IOException {
    return docs(shardName, new int[]{docId}, fieldNames)[0];
  }

  /**
   * Returns specifed Lucene documents from a givne shard where all or only the
   * given fields are loaded from the index.
   *
   * @param shardName shard to access
   * @param docIds doc IDs to read
   * @param fieldNames array of fields to get, or null to get all fields
   * @return array of Documents in the same order as the provided docId array
   * @throws IOException
   */
  protected Document[] docs(final String shardName, final int[] docIds, final String[] fieldNames) throws IOException {
    final Document[] result = new Document[docIds.length];

    final IndexHandle handle = getSearcherHandleByShard(shardName);
    IndexSearcher searcher = handle.getSearcher();
    try {
      if (searcher != null) {
        int i = 0;
        for (int docId : docIds) {
        if (fieldNames == null) {
            result[i++] = searcher.doc(docId);
        } else {
            result[i++] = searcher.doc(docId, new HashSet<String>(Arrays.asList(fieldNames)));
        }
      }
      }
    } finally {
      handle.finishSearcher();
    }

    return result;
  }

  /**
   * Rewrites a query for the given shards
   * 
   * @param original
   * @param shardNames
   * @return
   * @throws IOException
   */
  protected Query rewrite(final Query original, final String[] shardNames) throws IOException {
    final Query[] queries = new Query[shardNames.length];
    for (int i = 0; i < shardNames.length; i++) {
      final String shard = shardNames[i];
      final IndexHandle handle = getSearcherHandleByShard(shard);
      IndexSearcher searcher = handle.getSearcher();
      try {
        if (searcher == null) {
          throw new IllegalStateException("no index-server for shard '" + shard + "' found - probably undeployed");
        } else {
          queries[i] = searcher.rewrite(original);
        }
      } finally {
        handle.finishSearcher();
      }
    }
    if (queries.length > 0 && queries[0] != null) {
      return combineQuery(queries);
    } else {
      LOG.error("No queries available for shards: " + Arrays.toString(shardNames));
    }
    return original;
  }

  // Taken from Lucene 3.5 Query.combine(queries)
  // TODO is this query combination valid for Lucene 4?
  private Query combineQuery(Query[] queries) {
    HashSet<Query> uniques = new HashSet<Query>();
    for (int i = 0; i < queries.length; i++) {
      Query query = queries[i];
      BooleanClause[] clauses = null;
      // check if we can split the query into clauses
      boolean splittable = (query instanceof BooleanQuery);
      if(splittable){
        BooleanQuery bq = (BooleanQuery) query;
        splittable = bq.isCoordDisabled();
        clauses = bq.getClauses();
        for (int j = 0; splittable && j < clauses.length; j++) {
          splittable = (clauses[j].getOccur() == BooleanClause.Occur.SHOULD);
        }
      }
      if(splittable){
        for (int j = 0; j < clauses.length; j++) {
          uniques.add(clauses[j].getQuery());
        }
      } else {
        uniques.add(query);
      }
    }
    // optimization: if we have just one query, just return it
    if(uniques.size() == 1){
      return uniques.iterator().next();
    }
    BooleanQuery result = new BooleanQuery(true);
    for (final Query query : uniques)
      result.add(query, BooleanClause.Occur.SHOULD);
    return result;

  }

  /**
   * Implements a single thread of a search. Each shard has a separate
   * SearchCall and they are run more or less in parallel.
   */
  protected class SearchCall implements Callable<SearchResult> {

    protected final String _shardName;
    protected final Query _query;
    protected final Map<TermWritable, Integer> _dfMap;
    protected final int _limit;
    protected final Sort _sort;
    protected final long _timeout;
    protected final int _callIndex;
    protected final Filter _filter;

    public SearchCall(String shardName, Query query, Map<TermWritable, Integer> dfMap, int limit, Sort sort, long timeout, int callIndex, Filter filter) {
      _shardName = shardName;
      _query = query;
      _dfMap = dfMap;
      _limit = limit;
      _sort = sort;
      _timeout = timeout;
      _callIndex = callIndex;
      _filter = filter;
    }

    @Override
    @SuppressWarnings({ "rawtypes" })
    public SearchResult call() throws Exception {
      IndexHandle handle = getSearcherHandleByShard(_shardName);
      IndexSearcher searcher = handle.getSearcher();
      IndexReader reader = searcher.getIndexReader();

      try {
        if (searcher == null) {
          LOG.warn(String.format("Search attempt for shard %s skipped because shard was closed; empty result returned",
                  _shardName));
          // return empty result...
          return new SearchResult(0, new ScoreDoc[0], _callIndex);
        }

        int nDocs = Math.min(_limit, reader.maxDoc());

        // empty index (or result limit <= 0); return empty results (as the
        // collectors will fail if nDocs <= 0)
        if (nDocs <= 0) {
          return new SearchResult(0, new ScoreDoc[0], _callIndex);
        }

        Weight weight = _query.createWeight(new WrappingGlobalDfSearcher(searcher, _dfMap));
        final Query query = new WeightWrapperQuery(_query, weight);

        TopDocsCollector resultCollector;
        if (_sort != null) {
          boolean fillFields = true;// see IndexSearcher#search(...)
          boolean fieldSortDoTrackScores = false;
          boolean fieldSortDoMaxScore = false;
          // createWeight(null) works because this weight doesn't delegate anything
          resultCollector = TopFieldCollector.create(_sort, nDocs, fillFields, fieldSortDoTrackScores,
              fieldSortDoMaxScore, !query.createWeight(null).scoresDocsOutOfOrder());
        } else {
          // createWeight(null) works because this weight doesn't delegate anything
          resultCollector = TopScoreDocCollector.create(nDocs, !query.createWeight(null).scoresDocsOutOfOrder());
        }
        try {
          searcher.search(_query, _filter, wrapInTimeoutCollector(resultCollector));
        } catch (TimeExceededException e) {
          LOG.warn("encountered exceeded timout for getQuery '" + _query + " on shard '" + _shardName
                  + "' with timeout set to '" + _timeout + "'");
        }
        TopDocs docs = resultCollector.topDocs();
        return new SearchResult(docs.totalHits, docs.scoreDocs, _callIndex);
      } finally {
        handle.finishSearcher();
      }
    }

    @SuppressWarnings({ "rawtypes" })
    private Collector wrapInTimeoutCollector(TopDocsCollector resultCollector) {
      if (_timeout <= 0) {
        return resultCollector;
      }

      TimeLimitingCollector timeoutCollector = new TimeLimitingCollector(resultCollector, _searchTimerCounter, _timeout);
      timeoutCollector.setBaseline();
      return timeoutCollector;
    }
  }

  protected static class SearchResult {

    protected final int _totalHits;
    protected final ScoreDoc[] _scoreDocs;
    protected int _searchCallIndex;

    public SearchResult(int totalHits, ScoreDoc[] scoreDocs, int searchCallIndex) {
      _totalHits = totalHits;
      _scoreDocs = scoreDocs;
      _searchCallIndex = searchCallIndex;
    }

    public int getTotalHits() {
      return _totalHits;
    }

    public ScoreDoc[] getScoreDocs() {
      return _scoreDocs;
    }

    public int getSearchCallIndex() {
      return _searchCallIndex;
    }

  }

  protected static class KattaHitQueue extends PriorityQueue<Hit> implements Iterable<Hit> {

    private final int _maxSize;

    public KattaHitQueue(final int maxSize) {
      super(maxSize);
      _maxSize = maxSize;
    }

    public boolean insert(Hit hit) {
      if (size() < _maxSize) {
        add(hit);
        return true;
      }
      if (lessThan(top(), hit)) {
        insertWithOverflow(hit);
        return true;
      }
      return false;
    }

    @Override
    protected final boolean lessThan(final Hit hitA, final Hit hitB) {
      return hitA.compareTo(hitB) > 0;
    }

    @Override
    public Iterator<Hit> iterator() {
      return new Iterator<Hit>() {
        @Override
        public boolean hasNext() {
          return KattaHitQueue.this.size() > 0;
        }

        @Override
        public Hit next() {
          return KattaHitQueue.this.pop();
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException("Can't remove using this iterator");
        }
      };
    }
  }

}
