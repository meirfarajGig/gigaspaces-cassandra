package org.openspaces.persistency.cassandra;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Row;
import org.openspaces.persistency.cassandra.error.SpaceCassandraSerializationException;
import org.openspaces.persistency.cassandra.meta.ColumnFamilyMetadata;
import org.openspaces.persistency.cassandra.meta.ColumnMetadata;
import org.openspaces.persistency.cassandra.meta.data.ColumnData;
import org.openspaces.persistency.cassandra.meta.data.ColumnFamilyRow;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class DataStaxUtils {

    public static final String PROPERTY_COLUMN_NAME = "column1";
    public static final String PROPERTY_COLUMN_VALUE = "value";

    private static Map<Class<?>, DataType> CLASS_TO_DATASTAX_TYPE_MAP = new HashMap<Class<?>, DataType>();

    private static ClassLoader CURRENT_CLASS_LOADER = DataStaxUtils.class.getClassLoader();

    static {
        CLASS_TO_DATASTAX_TYPE_MAP.put(String.class, DataType.text());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Boolean.class, DataType.cboolean());
        CLASS_TO_DATASTAX_TYPE_MAP.put(boolean.class, DataType.cboolean());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Byte.class, DataType.blob());
        CLASS_TO_DATASTAX_TYPE_MAP.put(byte.class, DataType.blob());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Character.class, DataType.blob());
        CLASS_TO_DATASTAX_TYPE_MAP.put(char.class, DataType.blob());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Double.class, DataType.cdouble());
        CLASS_TO_DATASTAX_TYPE_MAP.put(double.class, DataType.cdouble());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Float.class, DataType.cfloat());
        CLASS_TO_DATASTAX_TYPE_MAP.put(float.class, DataType.cfloat());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Integer.class, DataType.cint());
        CLASS_TO_DATASTAX_TYPE_MAP.put(int.class, DataType.cint());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Long.class, DataType.bigint());
        CLASS_TO_DATASTAX_TYPE_MAP.put(long.class, DataType.bigint());
        CLASS_TO_DATASTAX_TYPE_MAP.put(Short.class, DataType.blob());
        CLASS_TO_DATASTAX_TYPE_MAP.put(short.class, DataType.blob());
        CLASS_TO_DATASTAX_TYPE_MAP.put(java.math.BigDecimal.class, DataType.decimal());
        CLASS_TO_DATASTAX_TYPE_MAP.put(java.math.BigInteger.class, DataType.varint());
        CLASS_TO_DATASTAX_TYPE_MAP.put(java.util.UUID.class, DataType.uuid());
        CLASS_TO_DATASTAX_TYPE_MAP.put(java.util.Date.class, DataType.timestamp());
    }

    public static ConsistencyLevel xapConsistencyLevelToDataStax(CassandraConsistencyLevel level) {
        switch (level) {
            case ANY:
                return ConsistencyLevel.ANY;
            case ONE:
                return ConsistencyLevel.ONE;
            case QUORUM:
                return ConsistencyLevel.QUORUM;
            case ALL:
                return ConsistencyLevel.ALL;
            case LOCAL_QUORUM:
                return ConsistencyLevel.LOCAL_QUORUM;
            case EACH_QUORUM:
                return ConsistencyLevel.EACH_QUORUM;
            default:
                throw new IllegalArgumentException(String.format("Unsupported consistency level: %s", level));
        }
    }

    public static CassandraConsistencyLevel dataStaxConsistencyLevelToXap(ConsistencyLevel level) {
        switch (level) {
            case ANY:
                return CassandraConsistencyLevel.ANY;
            case ONE:
                return CassandraConsistencyLevel.ONE;
            case QUORUM:
                return CassandraConsistencyLevel.QUORUM;
            case ALL:
                return CassandraConsistencyLevel.ALL;
            case LOCAL_QUORUM:
                return CassandraConsistencyLevel.LOCAL_QUORUM;
            case EACH_QUORUM:
                return CassandraConsistencyLevel.EACH_QUORUM;
            case TWO:
            case THREE:
            case SERIAL:
            case LOCAL_SERIAL:
            case LOCAL_ONE:
            default:
                throw new IllegalArgumentException(String.format("Unsupported consistency level: %s", level));
        }
    }

    public static ColumnFamilyRow mapToColumnFamilyRow(Row row, ColumnFamilyMetadata columnFamilyMetadata) {
        List<ColumnData> columns = new LinkedList<ColumnData>();
        Object keyValue = null;

        for (ColumnDefinitions.Definition definition : row.getColumnDefinitions()) {
            String columnName = definition.getName();
            if (columnName.equals(columnFamilyMetadata.getKeyName())) {
                keyValue = row.getObject(columnName);
            } else {
                Object columnValue = row.getObject(columnName);
                ColumnMetadata columnMetadata = columnFamilyMetadata.getColumns().get(columnName);
                columns.add(new ColumnData(columnValue, columnMetadata));
            }
        }

        ColumnFamilyRow columnFamilyRow = new ColumnFamilyRow(columnFamilyMetadata, keyValue,
                ColumnFamilyRow.ColumnFamilyRowType.Read);

        for (ColumnData columnData : columns) {
            columnFamilyRow.addColumnData(columnData);
        }

        return columnFamilyRow;
    }

    public static DataType classToDataStaxType(Class<?> cls) {
        return CLASS_TO_DATASTAX_TYPE_MAP.getOrDefault(cls, DataType.blob());
    }

    public static Object byteBufferToObject(ByteBuffer byteBuffer) {
        try {
            int length = byteBuffer.remaining();
            ByteArrayInputStream bais = new ByteArrayInputStream(byteBuffer.array(),
                    byteBuffer.arrayOffset() + byteBuffer.position(),
                    length);
            ObjectInputStream ois = new CustomObjectInputStream(bais);
            Object object = ois.readObject();
            byteBuffer.position(byteBuffer.position() + (length - ois.available()));
            ois.close();
            return object;
        } catch (Exception e) {
            throw new SpaceCassandraSerializationException("Failed deserializing object", e);
        }
    }

    public static ByteBuffer objectToByteBuffer(Object value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(value);
            oos.close();
            byte[] objectBytes = baos.toByteArray();
            return ByteBuffer.wrap(objectBytes);
        } catch (IOException e) {
            throw new SpaceCassandraSerializationException("Failed serializing object " + value, e);
        }
    }

    private static class CustomObjectInputStream extends ObjectInputStream {

        CustomObjectInputStream(InputStream is) throws IOException {
            super(is);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass objectStreamClass) throws ClassNotFoundException {
            return Class.forName(objectStreamClass.getName(), false, CURRENT_CLASS_LOADER);
        }

    }
}
