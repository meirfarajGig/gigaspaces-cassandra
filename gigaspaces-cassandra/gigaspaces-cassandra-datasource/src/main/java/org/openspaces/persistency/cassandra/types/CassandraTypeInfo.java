package org.openspaces.persistency.cassandra.types;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.mapper.MapperContext;
import com.datastax.oss.driver.api.mapper.entity.EntityHelper;
import com.datastax.oss.driver.api.querybuilder.select.Select;
import com.datastax.oss.driver.internal.mapper.DefaultMapperContext;
import com.datastax.oss.driver.internal.mapper.entity.EntityHelperBase;
import org.openspaces.persistency.cassandra.error.SpaceCassandraTypeIntrospectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentHashMap;

public class CassandraTypeInfo {
    private static Logger logger = LoggerFactory.getLogger(CassandraTypeInfo.class);

    private final Class<? extends EntityHelperBase> entityHelpersClass;
    private final Class<?>      type;
    private final CqlIdentifier defaultKeyspaceId;
    private final Constructor<? extends EntityHelperBase> entityHelperConstructor;
    private static final CqlIdentifier APPLIED = CqlIdentifier.fromInternal("[applied]");

    public CassandraTypeInfo(
            Class<? extends EntityHelperBase> entityHelpersClass,
            String defaultKeyspace,
            CqlSession session) throws SpaceCassandraTypeIntrospectionException {
        this.entityHelpersClass=entityHelpersClass;
        this.defaultKeyspaceId=CqlIdentifier.fromCql(defaultKeyspace);
        try {
            entityHelperConstructor = entityHelpersClass.getDeclaredConstructor(MapperContext.class);
        } catch (NoSuchMethodException e) {
            String msg = "Fail to retrieve entityHelpersClass="+entityHelpersClass.getName()+" constructor with mapperContext";
            logger.error(msg,e);
            throw new SpaceCassandraTypeIntrospectionException(msg,e);
        }
        EntityHelperBase<?> mb = getEntityHelper(session) ;
        this.type=mb.getEntityClass();
/*        this.spaceTypeDescriptor = new SpaceTypeDescriptorBuilder(this.type.getName())
                .create();*/
        //TODO : keyId????
        logger.info("retrieve entity class {} for {} ",type,entityHelpersClass);
    }

    public Select getBaseSelectQuery(CqlSession session){
        EntityHelperBase<?> entityHelperBase = getEntityHelper(session);
        return entityHelperBase.selectStart();
    }

    public Select getAllSelectQuery(CqlSession session){
        Select select= getBaseSelectQuery(session);
        return select.all();
    }

    public EntityHelperBase<?> getEntityHelper(CqlSession session) throws SpaceCassandraTypeIntrospectionException{
        try {
            return entityHelperConstructor.newInstance(getMapperContext(session));
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            String msg = "Fail to create new entityHelper "+entityHelpersClass.getName()+" with "+entityHelperConstructor;
            logger.error(msg,e);
            throw new SpaceCassandraTypeIntrospectionException(msg,e);
        }
    }

    public DefaultMapperContext getMapperContext(CqlSession session){
        return new DefaultMapperContext(
                session,
                defaultKeyspaceId,
                new ConcurrentHashMap<>()); // TODO : think about if we need or not custom state ?
    }

    public Class<? extends EntityHelperBase> getEntityHelpersClass() {
        return entityHelpersClass;
    }

    public Class<?> getType() {
        return type;
    }

    public CqlIdentifier getDefaultKeyspaceId() {
        return defaultKeyspaceId;
    }

    public  <EntityT> EntityT asEntity(Row row, EntityHelper<EntityT> entityHelper) {
        return (row == null
                // Special case for INSERT IF NOT EXISTS. If the row did not exists, the query returns
                // only [applied], we want to return null to indicate there was no previous entity
                || (row.getColumnDefinitions().size() == 1
                && row.getColumnDefinitions().get(0).getName().equals(APPLIED)))
                ? null
                : entityHelper.get(row);
    }
}
