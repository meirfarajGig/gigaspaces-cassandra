package org.openspaces.persistency.cassandra.datasource;

import com.datastax.driver.core.Row;
import org.openspaces.persistency.cassandra.DataStaxUtils;
import org.openspaces.persistency.cassandra.meta.ColumnFamilyMetadata;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;

import java.util.Iterator;

public class DataStaxIterator implements Iterator<ColumnFamilyRow> {

    private final Iterator<Row> sourceIterator;
    private final ColumnFamilyMetadata columnFamilyMetadata;

    public DataStaxIterator(Iterator<Row> sourceIterator, ColumnFamilyMetadata columnFamilyMetadata) {
        this.sourceIterator = sourceIterator;
        this.columnFamilyMetadata = columnFamilyMetadata;
    }

    public ColumnFamilyRow next() {
        return DataStaxUtils.mapToColumnFamilyRow(sourceIterator.next(), columnFamilyMetadata);
    }

    public boolean hasNext() {
        return sourceIterator.hasNext();
    }
}
