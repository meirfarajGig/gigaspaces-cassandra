package org.openspaces.persistency.cassandra;

import com.gigaspaces.document.SpaceDocument;
import org.openspaces.persistency.cassandra.meta.ColumnFamilyMetadata;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;
import org.openspaces.persistency.cassandra.meta.mapping.SpaceDocumentColumnFamilyMapper;

import java.util.List;
import java.util.Map;

public interface CassandraClient {

    void close();

    CassandraConsistencyLevel getReadConsistencyLevel();

    CassandraConsistencyLevel getWriteConsistencyLevel();

    void createMetadataColumnFamilyColumnFamilyIfNecessary();

    ColumnFamilyMetadata getColumnFamilyMetadata(String typeName);

    ColumnFamilyMetadata fetchColumnFamilyMetadata(String typeName, SpaceDocumentColumnFamilyMapper mapper);

    SpaceDocument readDocmentByKey(SpaceDocumentColumnFamilyMapper mapper,
                                          String typeName, Object keyValue);

    Map<Object, SpaceDocument> readDocumentsByKeys(SpaceDocumentColumnFamilyMapper mapper,
                                                          String typeName, Object[] keyValues);

    Map<String,ColumnFamilyMetadata> getColumnFamiliesMetadata();

    Map<String,ColumnFamilyMetadata> populateColumnFamiliesMetadata(SpaceDocumentColumnFamilyMapper mapper);

    void createColumnFamilyIfNecessary(ColumnFamilyMetadata metadata, boolean shouldPersist);

    void addIndexesToColumnFamily(String typeName, List<String> columnNames, SpaceDocumentColumnFamilyMapper mapper);

    void performBatchOperation(List<ColumnFamilyRow> rows);
}
