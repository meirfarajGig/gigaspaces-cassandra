package org.openspaces.persistency.cassandra.datasource;

import com.gigaspaces.datasource.DataIterator;
import com.gigaspaces.document.SpaceDocument;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openspaces.persistency.cassandra.meta.ColumnFamilyMetadata;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;
import org.openspaces.persistency.cassandra.meta.mapping.SpaceDocumentColumnFamilyMapper;

import java.util.*;

public class CassandraTokenRangeDataStaxDataIterator implements DataIterator<Object> {

    private static final Log logger = LogFactory.getLog(CassandraTokenRangeDataStaxDataIterator.class);

    private final SpaceDocumentColumnFamilyMapper mapper;
    private final Iterator<ColumnFamilyRow> columnFamilyRowIterator;
    private final ColumnFamilyMetadata columnFamilyMetadata;
    private final int limit;
    private Object currentLastToken;
    private int currentTotalCount = 0;

    public CassandraTokenRangeDataStaxDataIterator(SpaceDocumentColumnFamilyMapper mapper,
                                                   Iterator<ColumnFamilyRow> columnFamilyRowIterator,
                                                   ColumnFamilyMetadata columnFamilyMetadata,
                                                   CQLQueryContext queryContext,
                                                   Object lastToken,
                                                   int limit) {
        if (logger.isTraceEnabled()) {
            logger.trace("Creating range data iterator for query: " + queryContext + " for type: " + columnFamilyMetadata.getTypeName() +
                    ", limit="+limit + ", starting from token(" + (lastToken != null ? lastToken : "FIRST_IN_RING") + ")");
        }

        this.mapper = mapper;
        this.columnFamilyRowIterator = columnFamilyRowIterator;
        this.columnFamilyMetadata = columnFamilyMetadata;
        this.limit = limit;
    }

    @Override
    public boolean hasNext() {
        return columnFamilyRowIterator.hasNext();
    }

    @Override
    public SpaceDocument next() {
        while (columnFamilyRowIterator.hasNext()) {
            currentTotalCount++;
            SpaceDocument document = getNextDocument();
            currentLastToken = document.getProperty(columnFamilyMetadata.getKeyName());
            if (document.getProperties().size() > 1) {
                return document;
            }
        }
        return null;
    }

    @Override
    public void remove() {
    }

    @Override
    public void close() {
    }

    public Object getLastToken() {
        return currentLastToken;
    }

    public int getLimit() {
        return limit;
    }

    public int getCurrentTotalCount() {
        return currentTotalCount;
    }

    private SpaceDocument getNextDocument() {
        ColumnFamilyRow columnFamilyRow = columnFamilyRowIterator.next();
        return mapper.toDocument(columnFamilyRow);
    }
}
