/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

/**
 * Executor 基类，提供骨架方法，从而使子类只要实现指定的几个抽象方法即可
 *
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

    private static final Log log = LogFactory.getLog(BaseExecutor.class);

    /**
     * 事务对象
     */
    protected Transaction transaction;
    /**
     * 包装的 Executor 对象
     */
    protected Executor wrapper;

    /**
     * DeferredLoad( 延迟加载 ) 队列
     */
    protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
    /**
     * 本地缓存，即一级缓存
     */
    protected PerpetualCache localCache;
    /**
     * 本地输出类型的参数的缓存
     */
    protected PerpetualCache localOutputParameterCache;
    protected Configuration configuration;

    /**
     * 记录嵌套查询的层级
     */
    protected int queryStack;
    /**
     * 当前Executor是否关闭
     */
    private boolean closed;

    protected BaseExecutor(Configuration configuration, Transaction transaction) {
        this.transaction = transaction;
        this.deferredLoads = new ConcurrentLinkedQueue<>();
        this.localCache = new PerpetualCache("LocalCache");
        this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
        this.closed = false;
        this.configuration = configuration;
        this.wrapper = this; // 自己
    }

    /**
     * 如果当前{@link Executor}已经关闭({@link #closed})，则抛出{@link ExecutorException}，否则返回{@link #transaction}
     *
     * @return
     */
    @Override
    public Transaction getTransaction() {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return transaction;
    }

    /**
     * <ol>
     *     <li>
     *         调用{@link BaseExecutor#rollback(boolean)}传入{@code forceRollback}进行回滚
     *     </li>
     *     <li>
     *         如果{@link BaseExecutor#transaction}非null，则{@link BaseExecutor#transaction}.close()
     *     </li>
     *     <li>
     *         将{@link BaseExecutor#transaction}、{@link BaseExecutor#deferredLoads}、{@link BaseExecutor#localCache}、{@link BaseExecutor#localOutputParameterCache}置为null；{@link BaseExecutor#closed}置为true
     *     </li>
     * </ol>
     *
     * @param forceRollback
     */
    @Override
    public void close(boolean forceRollback) {
        try {
            try {
                rollback(forceRollback);
            } finally {
                if (transaction != null) {
                    transaction.close();
                }
            }
        } catch (SQLException e) {
            // Ignore.  There's nothing that can be done at this point.
            log.warn("Unexpected exception on closing transaction.  Cause: " + e);
        } finally {
            transaction = null;
            deferredLoads = null;
            localCache = null;
            localOutputParameterCache = null;
            closed = true;
        }
    }

    /**
     * @return {@link #closed}
     */
    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * 接受一个{@link MappedStatement}类型参数{@code ms}和{@link Object}类型参数{@code parameter}
     * <ol>
     *     <li>
     *         使用{@link ErrorContext}进行{@code ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId())}将当前错误上下文与当前线程绑定。
     *     </li>
     *     <li>
     *         如果{@link BaseExecutor#closed}为true，则抛出异常{@link ExecutorException}，否则继续
     *     </li>
     *     <li>
     *         调用{@link BaseExecutor#clearLocalCache()}清除本地缓存
     *     </li>
     *     <li>
     *         调用{@link BaseExecutor#doUpdate(MappedStatement, Object)}方法将两个入参分别传入执行真正的update操作，该方法为抽象方法，由{@link BaseExecutor}的继承类实现
     *     </li>
     * </ol>
     *
     * @param ms
     * @param parameter
     * @return
     * @throws SQLException
     */
    @Override
    public int update(MappedStatement ms, Object parameter) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        clearLocalCache();
        return doUpdate(ms, parameter);
    }

    /**
     * 调用{@link BaseExecutor#doFlushStatements(boolean)}（传入false）将当前{@link Executor}中的所有Statement都刷入数据库
     *
     * @return
     * @throws SQLException
     */
    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return flushStatements(false);
    }

    /**
     * 如果当前{@link Executor}对象已经关闭({@link #closed})，则抛出异常{@link ExecutorException}，否则调用{@link #doFlushStatements(boolean)}
     *
     * @param isRollBack
     * @return
     * @throws SQLException
     */
    public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return doFlushStatements(isRollBack);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        // 已经关闭，则抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 清空本地缓存，如果 queryStack 为零，并且要求清空本地缓存。
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            clearLocalCache();
        }
        List<E> list;
        try {
            // queryStack + 1
            queryStack++;
            // 从一级缓存中，获取查询结果
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            // 获取到，则进行处理
            if (list != null) {
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            // 获得不到，则从数据库中查询
            } else {
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            // queryStack - 1
            queryStack--;
        }
        if (queryStack == 0) {
            // 执行延迟加载
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // issue #601
            // 清空 deferredLoads
            deferredLoads.clear();
            // 如果缓存级别是 LocalCacheScope.STATEMENT ，则进行清理
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                // issue #482
                clearLocalCache();
            }
        }
        return list;
    }

    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        // 获得 BoundSql 对象
        BoundSql boundSql = ms.getBoundSql(parameter);
        // 执行查询
        return doQueryCursor(ms, parameter, rowBounds, boundSql);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        // 如果执行器已关闭，抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 创建 DeferredLoad 对象
        DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
        // 如果可加载，则执行加载
        if (deferredLoad.canLoad()) {
            deferredLoad.load();
        // 如果不可加载，则添加到 deferredLoads 中
        } else {
            deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
        }
    }

    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 创建 CacheKey 对象
        CacheKey cacheKey = new CacheKey();
        // 设置 id、offset、limit、sql 到 CacheKey 对象中
        cacheKey.update(ms.getId());
        cacheKey.update(rowBounds.getOffset());
        cacheKey.update(rowBounds.getLimit());
        cacheKey.update(boundSql.getSql());
        // 设置 ParameterMapping 数组的元素对应的每个 value 到 CacheKey 对象中
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
        // mimic DefaultParameterHandler logic 这块逻辑，和 DefaultParameterHandler 获取 value 是一致的。
        for (ParameterMapping parameterMapping : parameterMappings) {
            if (parameterMapping.getMode() != ParameterMode.OUT) {
                Object value;
                String propertyName = parameterMapping.getProperty();
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    value = boundSql.getAdditionalParameter(propertyName);
                } else if (parameterObject == null) {
                    value = null;
                } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                cacheKey.update(value);
            }
        }
        // 设置 Environment.id 到 CacheKey 对象中
        if (configuration.getEnvironment() != null) {
            // issue #176
            cacheKey.update(configuration.getEnvironment().getId());
        }
        return cacheKey;
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return localCache.getObject(key) != null;
    }

    @Override
    public void commit(boolean required) throws SQLException {
        // 已经关闭，则抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Cannot commit, transaction is already closed");
        }
        // 清空本地缓存
        clearLocalCache();
        // 刷入批处理语句
        flushStatements();
        // 是否要求提交事务。如果是，则提交事务。
        if (required) {
            transaction.commit();
        }
    }

    /**
     * 调用{@link BaseExecutor#clearLocalCache()}清除缓存和{@link BaseExecutor#flushStatements(boolean)}(传入true)，然后根据required判断是否调用{@link #transaction}.rollback()
     *
     * @param required 是否回滚真正的数据库事务
     * @throws SQLException
     */
    @Override
    public void rollback(boolean required) throws SQLException {
        if (!closed) {
            try {
                clearLocalCache();
                flushStatements(true);
            } finally {
                if (required) {
                    transaction.rollback();
                }
            }
        }
    }

    /**
     * 清除本地缓存:{@link #localCache}.clear()和{@link #localOutputParameterCache}.clear()
     */
    @Override
    public void clearLocalCache() {
        if (!closed) {
            localCache.clear();
            localOutputParameterCache.clear();
        }
    }

    /**
     * 执行update操作，具体实现看：
     * <ul>
     *     <li>
     *         {@link BatchExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     *     <li>
     *         {@link org.apache.ibatis.executor.loader.ResultLoaderMap.ClosedExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     *     <li>
     *         {@link ReuseExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     *     <li>
     *         {@link SimpleExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     * </ul>
     *
     * @param ms
     * @param parameter
     * @return
     * @throws SQLException
     */
    protected abstract int doUpdate(MappedStatement ms, Object parameter)
            throws SQLException;

    /**
     * 本方法为抽象方法，大概逻辑为：
     * <ul>
     *     是否因为回滚而调用本方法（{@code isRollback}）
     *     <li>
     *         如果是则关闭{@link Executor}持有的所有{@link Statement}对象然后返回一个空的{@link List}
     *     </li>
     *     <li>
     *         则将所有{@link Executor}中所有的{@link Statement}刷入数据库中，并关闭所有{@link Statement}，然后返回可能存在的{@link BatchResult}集合（目前只有{@link BatchExecutor}会返回一个非空集合，其他继承累都是{@link Collections#emptyList()}）
     *     </li>
     * </ul>
     * 具体看：
     * <ul>
     *     <li>
     *         {@link BatchExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     *     <li>
     *         {@link org.apache.ibatis.executor.loader.ResultLoaderMap.ClosedExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     *     <li>
     *         {@link ReuseExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     *     <li>
     *         {@link SimpleExecutor#doUpdate(MappedStatement, Object)}
     *     </li>
     * </ul>
     * @param isRollback
     * @return
     * @throws SQLException
     */
    protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
            throws SQLException;

    protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException;

    protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
            throws SQLException;

    // 关闭 Statement 对象
    protected void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                if (!statement.isClosed()) {
                    statement.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * 设置事务超时时间
     *
     * Apply a transaction timeout.
     * @param statement a current statement
     * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
     * @since 3.4.0
     * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
     */
    protected void applyTransactionTimeout(Statement statement) throws SQLException {
        StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
    }

    // 暂时忽略，存储过程相关
    private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            final Object cachedParameter = localOutputParameterCache.getObject(key);
            if (cachedParameter != null && parameter != null) {
                final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
                final MetaObject metaParameter = configuration.newMetaObject(parameter);
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        final String parameterName = parameterMapping.getProperty();
                        final Object cachedValue = metaCachedParameter.getValue(parameterName);
                        metaParameter.setValue(parameterName, cachedValue);
                    }
                }
            }
        }
    }

    // 从数据库中读取操作
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        // 在缓存中，添加占位对象。此处的占位符，和延迟加载有关，可见 `DeferredLoad#canLoad()` 方法
        localCache.putObject(key, EXECUTION_PLACEHOLDER);
        try {
            // 执行读操作
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            // 从缓存中，移除占位对象
            localCache.removeObject(key);
        }
        // 添加到缓存中
        localCache.putObject(key, list);
        // 暂时忽略，存储过程相关
        if (ms.getStatementType() == StatementType.CALLABLE) {
            localOutputParameterCache.putObject(key, parameter);
        }
        return list;
    }

    // 获得 Connection 对象
    protected Connection getConnection(Log statementLog) throws SQLException {
        // 获得 Connection 对象
        Connection connection = transaction.getConnection();
        // 如果 debug 日志级别，则创建 ConnectionLogger 对象，进行动态代理
        if (statementLog.isDebugEnabled()) {
            return ConnectionLogger.newInstance(connection, statementLog, queryStack);
        } else {
            return connection;
        }
    }

    @Override
    public void setExecutorWrapper(Executor wrapper) {
        this.wrapper = wrapper;
    }

    private static class DeferredLoad {

        private final MetaObject resultObject;
        private final String property;
        private final Class<?> targetType;
        private final CacheKey key;
        private final PerpetualCache localCache;
        private final ObjectFactory objectFactory;
        private final ResultExtractor resultExtractor;

        // issue #781
        public DeferredLoad(MetaObject resultObject,
                            String property,
                            CacheKey key,
                            PerpetualCache localCache,
                            Configuration configuration,
                            Class<?> targetType) {
            this.resultObject = resultObject;
            this.property = property;
            this.key = key;
            this.localCache = localCache;
            this.objectFactory = configuration.getObjectFactory();
            this.resultExtractor = new ResultExtractor(configuration, objectFactory);
            this.targetType = targetType;
        }

        public boolean canLoad() {
            return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
        }

        public void load() {
            @SuppressWarnings("unchecked")
            // we suppose we get back a List
            // 从缓存 localCache 中获取
            List<Object> list = (List<Object>) localCache.getObject(key);
            // 解析结果
            Object value = resultExtractor.extractObjectFromList(list, targetType);
            // 设置到 resultObject 中
            resultObject.setValue(property, value);
        }

    }

}