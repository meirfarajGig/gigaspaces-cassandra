/*******************************************************************************
 * Copyright (c) 2012 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.openspaces.persistency.cassandra.datasource;

import com.gigaspaces.datasource.DataIterator;
import com.gigaspaces.document.SpaceDocument;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openspaces.persistency.cassandra.CassandraClient;
import org.openspaces.persistency.cassandra.CassandraSpaceDataSource;
import org.openspaces.persistency.cassandra.meta.ColumnFamilyMetadata;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;
import org.openspaces.persistency.cassandra.meta.mapping.SpaceDocumentColumnFamilyMapper;

import java.util.Iterator;

/**
 * @since 9.1.1
 * @author Dan Kilman
 */
public class CassandraTokenRangeAwareDataIterator implements DataIterator<Object> {
    
    private static final Log                      logger             = LogFactory.getLog(CassandraSpaceDataSource.class);
    
    private final SpaceDocumentColumnFamilyMapper mapper;
    private final CassandraClient cassandraClient;
    private final ColumnFamilyMetadata            columnFamilyMetadata;
    private final int                             maxResults;
    private final int                             batchLimit;
    private final CQLQueryContext queryContext;

    private CassandraTokenRangeDataStaxDataIterator currentIterator;
    private int                                   currentResultCount = 0;


    public CassandraTokenRangeAwareDataIterator(
            SpaceDocumentColumnFamilyMapper mapper,
            CassandraClient cassandraClient,
            ColumnFamilyMetadata columnFamilyMetadata,
            CQLQueryContext queryContext,
            int maxResults,
            int batchLimit) {
        if (logger.isTraceEnabled()) {
            logger.trace("Creating data iterator for query: " + queryContext + " for type: " + columnFamilyMetadata.getTypeName() +
                    ", batchLimit="+batchLimit);
        }
        
        this.mapper = mapper;
        this.cassandraClient = cassandraClient;
        this.columnFamilyMetadata = columnFamilyMetadata;
        this.queryContext = queryContext;
        this.maxResults = maxResults;
        this.batchLimit = batchLimit;
        this.currentIterator = nextDataIterator();
    }
    
    @Override
    public boolean hasNext() {
        while (currentIterator != null && !currentIterator.hasNext()) {
            currentIterator = nextDataIterator();
        }

        return currentIterator != null;
    }

    @Override
    public SpaceDocument next() {
        currentResultCount++;
        return currentIterator.next();
    }

    private CassandraTokenRangeDataStaxDataIterator nextDataIterator() {
        if (calculateRemainingResults() <= 0) {
            return null;
        }
        
        // indication this is the first time nextDataIterator() is called
        // so no last token exists yet
        if (currentIterator == null) {
            CassandraTokenRangeDataStaxDataIterator result = createIterator(null);
            
            // no need to continue with other iterators if this query returned no results
            // this will cause the next call to calculateRemainingResults() to return 0
            // thus ending our iterations
            if (result.getLastToken() == null) {
                currentResultCount = maxResults;
            }
            
            return result;
        } else {
            Object currentLastToken = currentIterator.getLastToken();
            if (currentLastToken == null || currentIterator.getCurrentTotalCount() < currentIterator.getLimit()) {
                // finish iteration condition
                return null;
            } else {
                return createIterator(currentLastToken);
            }
        }
    }

    private CassandraTokenRangeDataStaxDataIterator createIterator(Object lastToken) {
        int limit = calculateRemainingResults();
        Iterator<ColumnFamilyRow> columnFamilyRowIterator =
                cassandraClient.readDocumentByQuery(columnFamilyMetadata, queryContext, lastToken, limit);

        return new CassandraTokenRangeDataStaxDataIterator(mapper,
                                                           columnFamilyRowIterator,
                                                           columnFamilyMetadata,
                                                           queryContext,
                                                           lastToken,
                                                           limit);
    }

    private int calculateRemainingResults() {
        int maxRemaining = maxResults == Integer.MAX_VALUE ? Integer.MAX_VALUE : 
                                                             maxResults - currentResultCount;
        return maxRemaining >= batchLimit ? batchLimit : maxRemaining;
    }
    
    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove is not supported");
    }
    
    @Override
    public void close() {
        if (currentIterator != null) {
            currentIterator = null;
        }
    }
}
