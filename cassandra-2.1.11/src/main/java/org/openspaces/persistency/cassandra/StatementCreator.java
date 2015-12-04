package org.openspaces.persistency.cassandra;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.ReconnectionPolicy;
import com.datastax.driver.core.querybuilder.Delete;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import org.openspaces.persistency.cassandra.meta.ColumnFamilyMetadata;
import org.openspaces.persistency.cassandra.meta.data.ColumnData;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;
import org.springframework.jdbc.datasource.DataSourceUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class StatementCreator {

    // TODO: use readConsistencyLevel to create read statements for Cassandra Client
    private CassandraConsistencyLevel readConsistencyLevel;
    private CassandraConsistencyLevel writeConsistencyLevel;

    public StatementCreator(CassandraConsistencyLevel readConsistencyLevel, CassandraConsistencyLevel writeConsistencyLevel) {
        this.readConsistencyLevel = readConsistencyLevel;
        this.writeConsistencyLevel = writeConsistencyLevel;
    }

    public Statement createInsertStatement(ColumnFamilyMetadata metadata, String[] rawKeys, Object[] rawValues) {
        String columnFamilyName = Metadata.quote(metadata.getColumnFamilyName());
        String[] keys = quoteKeys(rawKeys);
        Object[] values = serializeValues(metadata, rawKeys, rawValues);

        return createInsertStatement(columnFamilyName, keys, values);
    }

    public Statement createInsertStatement(String columnFamilyName, String[] keys, Object[] values) {
        return createBatchInsertStatement(columnFamilyName, keys, values)
                .setConsistencyLevel(DataStaxUtils.xapConsistencyLevelToDataStax(writeConsistencyLevel));
    }

    public List<RegularStatement> createBatchInsertStatement(ColumnFamilyRow columnFamilyRow) {
        if (isRepresentingSpaceDocument(columnFamilyRow.getColumnFamilyMetadata())) {
            return createSpaceDocBatchInsertStatements(columnFamilyRow);
        } else {
            List<RegularStatement> statements = new LinkedList<RegularStatement>();
            statements.add(createBatchStatement(columnFamilyRow, true));
            return statements;
        }
    }

    private List<RegularStatement> createSpaceDocBatchInsertStatements(ColumnFamilyRow columnFamilyRow) {
        List<RegularStatement> statements = new LinkedList<RegularStatement>();

        ColumnFamilyMetadata metadata = columnFamilyRow.getColumnFamilyMetadata();
        List<String> rawColumnsList = new ArrayList<String>();
        List<Object> rawValuesList = new ArrayList<Object>();

        String keyName = Metadata.quote(columnFamilyRow.getColumnFamilyMetadata().getKeyName());
        Class<?> keyType = columnFamilyRow.getColumnFamilyMetadata().getKeyType();
        Object keyValue = serializeValue(keyType, columnFamilyRow.getKeyValue());

        for (ColumnData columnData : columnFamilyRow.getColumns().values()) {
            if (columnData.getValue() != null) {
                rawColumnsList.add(columnData.getColumnMetadata().getFullName());
                rawValuesList.add(columnData.getValue());
            }
        }
        String[] rawKeys = rawColumnsList.toArray(new String[rawColumnsList.size()]);
        Object[] rawValues = rawValuesList.toArray(new Object[rawValuesList.size()]);

        String columnFamilyName = Metadata.quote(metadata.getColumnFamilyName());
        String[] keys = quoteKeys(rawKeys);
        Object[] values = serializeValues(metadata, rawKeys, rawValues);

        String[] propertyKeys = {keyName, Metadata.quote(DataStaxUtils.PROPERTY_COLUMN_NAME),
                Metadata.quote(DataStaxUtils.PROPERTY_COLUMN_VALUE)};
        for (int i = 0; i < keys.length; i++) {
            Object[] propertyValues = {keyValue, rawKeys[i], values[i]};
            statements.add(createBatchInsertStatement(columnFamilyName, propertyKeys, propertyValues));
        }

        return statements;
    }

    public List<RegularStatement> createBatchDeleteStatement(ColumnFamilyRow columnFamilyRow) {
        List<RegularStatement> statements = new LinkedList<RegularStatement>();
        if (isRepresentingSpaceDocument(columnFamilyRow.getColumnFamilyMetadata())) {
            statements.add(createSpaceDocBatchDeleteStatement(columnFamilyRow));
        } else {
            statements.add(createBatchStatement(columnFamilyRow, false));
        }

        return statements;
    }

    private RegularStatement createSpaceDocBatchDeleteStatement(ColumnFamilyRow columnFamilyRow) {
        ColumnFamilyMetadata metadata = columnFamilyRow.getColumnFamilyMetadata();
        String columnFamilyName = Metadata.quote(metadata.getColumnFamilyName());
        String keyName = Metadata.quote(columnFamilyRow.getColumnFamilyMetadata().getKeyName());
        Class<?> keyType = columnFamilyRow.getColumnFamilyMetadata().getKeyType();
        Object keyValue = serializeValue(keyType, columnFamilyRow.getKeyValue());

        return createBatchDeleteStatement(columnFamilyName, new String[]{keyName}, new Object[]{keyValue});
    }

    private RegularStatement createBatchStatement(ColumnFamilyRow columnFamilyRow, boolean insert) {
        ColumnFamilyMetadata metadata = columnFamilyRow.getColumnFamilyMetadata();
        List<String> rawColumnsList = new ArrayList<String>();
        List<Object> rawValuesList = new ArrayList<Object>();

        rawColumnsList.add(columnFamilyRow.getColumnFamilyMetadata().getKeyName());
        rawValuesList.add(columnFamilyRow.getKeyValue());

        for (ColumnData columnData : columnFamilyRow.getColumns().values()) {
            if (columnData.getValue() != null) {
                rawColumnsList.add(columnData.getColumnMetadata().getFullName());
                rawValuesList.add(columnData.getValue());
            }
        }
        String[] rawKeys = rawColumnsList.toArray(new String[rawColumnsList.size()]);
        Object[] rawValues = rawValuesList.toArray(new Object[rawValuesList.size()]);

        String columnFamilyName = Metadata.quote(metadata.getColumnFamilyName());
        String[] keys = quoteKeys(rawKeys);
        Object[] values = serializeValues(metadata, rawKeys, rawValues);

        if (insert) {
            return createBatchInsertStatement(columnFamilyName, keys, values);
        } else {
            return createBatchDeleteStatement(columnFamilyName, keys, values);
        }
    }

    private RegularStatement createBatchInsertStatement(String columnFamilyName, String[] keys, Object[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Number of passed insert statement keys does not match query values");
        }

        return QueryBuilder
                .insertInto(columnFamilyName)
                .values(keys, values);
    }

    // TODO: delete only BY KEY ID?
    private RegularStatement createBatchDeleteStatement(String columnFamilyName, String[] keys, Object[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Number of passed delete statement keys does not match query values");
        }

        Delete.Conditions deleteStatement = QueryBuilder
                .delete()
                .all()
                .from(columnFamilyName)
                .onlyIf();

        for (int i = 0; i < keys.length; i++) {
            deleteStatement.and(QueryBuilder.eq(keys[i], values[i]));
        }

        return deleteStatement;
    }

    private String[] quoteKeys(String[] rawKeys) {
        String[] keys = new String[rawKeys.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = Metadata.quote(rawKeys[i]);
        }
        return keys;
    }

    private Object[] serializeValues(ColumnFamilyMetadata metadata, String[] rawKeys, Object[] rawValues) {
        Object[] values = new Object[rawValues.length];
        for (int i = 0; i < values.length; i++) {
            if (rawKeys[i].equals(metadata.getKeyName())) {
                values[i] = serializeValue(metadata.getKeyType(), rawValues[i]);
            } else if (metadata.getColumns().containsKey(rawKeys[i])) {
                values[i] = serializeValue(metadata.getColumns().get(rawKeys[i]).getType(), rawValues[i]);
            } else {
                values[i] = serializeValue(null, rawValues[i]);
            }
        }

        return values;
    }

    private Object serializeValue(Class<?> columnClass, Object rawValue) {
        DataType columnType;
        if (columnClass == null) {
            columnType = DataType.blob();
        } else {
            columnType = DataStaxUtils.classToDataStaxType(columnClass);
        }
        // TODO: classes like Short might require too much data after serialization (expected a few bytes)
        return columnType == DataType.blob() ? DataStaxUtils.objectToByteBuffer(rawValue) : rawValue;
    }

    private boolean isRepresentingSpaceDocument(ColumnFamilyMetadata metadata) {
        return metadata.getTypeDescriptorData() != null &&
                metadata.getTypeDescriptorData().getTypeDescriptor().supportsDynamicProperties();
    }
}
