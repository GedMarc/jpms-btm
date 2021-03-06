/*
 * Copyright (C) 2006-2013 Bitronix Software (http://www.bitronix.be)
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
package bitronix.tm.resource.jdbc;

import bitronix.tm.internal.LogDebugCheck;
import bitronix.tm.internal.XAResourceHolderState;
import bitronix.tm.recovery.RecoveryException;
import bitronix.tm.resource.ResourceConfigurationException;
import bitronix.tm.resource.ResourceObjectFactory;
import bitronix.tm.resource.ResourceRegistrar;
import bitronix.tm.resource.common.RecoveryXAResourceHolder;
import bitronix.tm.resource.common.ResourceBean;
import bitronix.tm.resource.common.XAPool;
import bitronix.tm.resource.common.XAResourceProducer;
import bitronix.tm.utils.ManagementRegistrar;

import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import javax.sql.DataSource;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Implementation of a JDBC {@link DataSource} wrapping vendor's {@link XADataSource} implementation.
 *
 * @author Ludovic Orban
 * @author Brett Wooldridge
 */
@SuppressWarnings("serial")
public class PoolingDataSource
		extends ResourceBean
		implements DataSource, XAResourceProducer<JdbcPooledConnection, JdbcPooledConnection>, PoolingDataSourceMBean
{

	private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(PoolingDataSource.class.toString());
	private final transient List<ConnectionCustomizer> connectionCustomizers = new CopyOnWriteArrayList<>();
	private transient volatile XAPool<JdbcPooledConnection, JdbcPooledConnection> pool;
	private transient volatile XADataSource xaDataSource;
	private transient volatile RecoveryXAResourceHolder recoveryXAResourceHolder;
	private transient volatile Connection recoveryConnectionHandle;
	private transient volatile Map<XAResource, JdbcPooledConnection> xaResourceHolderMap;
	private volatile String testQuery;
	private volatile boolean enableJdbc4ConnectionTest;
	private volatile int connectionTestTimeout;
	private volatile int preparedStatementCacheSize = 0;
	private volatile String isolationLevel;
	private volatile String cursorHoldability;
	private volatile String localAutoCommit;
	private volatile String jmxName;

	/**
	 * Initialize all properties with their default values.
	 */
	public PoolingDataSource()
	{
		xaResourceHolderMap = new ConcurrentHashMap<>();
	}

	/**
	 * @return the wrapped XADataSource.
	 */
	public XADataSource getXaDataSource()
	{
		return xaDataSource;
	}

	/**
	 * Inject a pre-configured XADataSource instead of relying on className and driverProperties
	 * to build one. Upon deserialization the xaDataSource will be null and will need to be
	 * manually re-injected.
	 *
	 * @param xaDataSource
	 * 		the pre-configured XADataSource.
	 */
	public void setXaDataSource(XADataSource xaDataSource)
	{
		this.xaDataSource = xaDataSource;
	}

	/**
	 * @return the query that will be used to test connections.
	 */
	public String getTestQuery()
	{
		return testQuery;
	}

	/**
	 * When set, the specified query will be executed on the connection acquired from the pool before being handed to
	 * the caller. The connections won't be tested when not set. Default value is null.
	 *
	 * @param testQuery
	 * 		the query that will be used to test connections.
	 */
	public void setTestQuery(String testQuery)
	{
		this.testQuery = testQuery;
	}

	/**
	 * @return true if JDBC 4 isValid() testing should be performed, false otherwise.
	 */
	public boolean isEnableJdbc4ConnectionTest()
	{
		return enableJdbc4ConnectionTest;
	}

	/**
	 * When set and the underlying JDBC driver supports JDBC 4 isValid(), a Connection.isValid() call
	 * is performed to test the connection before handing it to the caller.
	 * If both testQuery and enableJdbc4ConnectionTest are set, enableJdbc4ConnectionTest takes precedence.
	 *
	 * @param enableJdbc4ConnectionTest
	 * 		true if JDBC 4 isValid() testing should be performed, false otherwise.
	 */
	public void setEnableJdbc4ConnectionTest(boolean enableJdbc4ConnectionTest)
	{
		this.enableJdbc4ConnectionTest = enableJdbc4ConnectionTest;
	}

	/**
	 * @return how many seconds each connection test will wait for a response,
	 * 		bounded above by the acquisition timeout.
	 */
	public int getEffectiveConnectionTestTimeout()
	{
		int t1 = getConnectionTestTimeout();
		int t2 = getAcquisitionTimeout();

		if ((t1 > 0) && (t2 > 0))
		{
			return Math.min(t1, t2);
		}
		else
		{
			return Math.max(t1, t2);
		}
	}

	/**
	 * @return how many seconds each connection test will wait for a response.
	 */
	public int getConnectionTestTimeout()
	{
		return connectionTestTimeout;
	}

	/**
	 * Determines how many seconds the connection test logic
	 * will wait for a response from the database.
	 *
	 * @param connectionTestTimeout
	 * 		connection timeout
	 */
	public void setConnectionTestTimeout(int connectionTestTimeout)
	{
		this.connectionTestTimeout = connectionTestTimeout;
	}

	/**
	 * @return the target maximum prepared statement cache size.
	 */
	public int getPreparedStatementCacheSize()
	{
		return preparedStatementCacheSize;
	}

	/**
	 * Set the target maximum size of the prepared statement cache.  In
	 * reality under certain unusual conditions the cache may temporarily
	 * drift higher in size.
	 *
	 * @param preparedStatementCacheSize
	 * 		the target maximum prepared statement cache size.
	 */
	public void setPreparedStatementCacheSize(int preparedStatementCacheSize)
	{
		this.preparedStatementCacheSize = preparedStatementCacheSize;
	}

	/**
	 * @return the default isolation level.
	 */
	public String getIsolationLevel()
	{
		return isolationLevel;
	}

	/**
	 * Set the default isolation level for connections.
	 *
	 * @param isolationLevel
	 * 		the default isolation level.
	 */
	public void setIsolationLevel(String isolationLevel)
	{
		this.isolationLevel = isolationLevel;
	}

	/**
	 * @return cursorHoldability the default cursor holdability.
	 */
	public String getCursorHoldability()
	{
		return cursorHoldability;
	}

	/**
	 * Set the default cursor holdability for connections.
	 *
	 * @param cursorHoldability
	 * 		the default cursor holdability.
	 */
	public void setCursorHoldability(String cursorHoldability)
	{
		this.cursorHoldability = cursorHoldability;
	}

	/**
	 * @return localAutoCommit the default local transactions autocommit mode.
	 */
	public String getLocalAutoCommit()
	{
		return localAutoCommit;
	}

	/**
	 * Set the default local transactions autocommit mode.
	 *
	 * @param localAutoCommit
	 * 		the default local transactions autocommit mode.
	 */
	public void setLocalAutoCommit(String localAutoCommit)
	{
		this.localAutoCommit = localAutoCommit;
	}

	/**
	 * Method addConnectionCustomizer ...
	 *
	 * @param connectionCustomizer
	 * 		of type ConnectionCustomizer
	 */
	public void addConnectionCustomizer(ConnectionCustomizer connectionCustomizer)
	{
		connectionCustomizers.add(connectionCustomizer);
	}

	/**
	 * Method removeConnectionCustomizer ...
	 *
	 * @param connectionCustomizer
	 * 		of type ConnectionCustomizer
	 */
	public void removeConnectionCustomizer(ConnectionCustomizer connectionCustomizer)
	{
		Iterator<ConnectionCustomizer> it = connectionCustomizers.iterator();
		while (it.hasNext())
		{
			ConnectionCustomizer customizer = it.next();
			if (customizer == connectionCustomizer)
			{
				it.remove();
				return;
			}
		}
	}

	/**
	 * Method fireOnAcquire ...
	 *
	 * @param connection
	 * 		of type Connection
	 */
	void fireOnAcquire(Connection connection)
	{
		for (ConnectionCustomizer connectionCustomizer : connectionCustomizers)
		{
			try
			{
				connectionCustomizer.onAcquire(connection, getUniqueName());
			}
			catch (Exception ex)
			{
				log.log(Level.WARNING, "ConnectionCustomizer.onAcquire() failed for " + connectionCustomizer, ex);
			}
		}
	}

	/**
	 * Method fireOnLease ...
	 *
	 * @param connection
	 * 		of type Connection
	 */
	void fireOnLease(Connection connection)
	{
		for (ConnectionCustomizer connectionCustomizer : connectionCustomizers)
		{
			try
			{
				connectionCustomizer.onLease(connection, getUniqueName());
			}
			catch (Exception ex)
			{
				log.log(Level.WARNING, "ConnectionCustomizer.onLease() failed for " + connectionCustomizer, ex);
			}
		}
	}

	/**
	 * Method fireOnRelease ...
	 *
	 * @param connection
	 * 		of type Connection
	 */
	void fireOnRelease(Connection connection)
	{
		for (ConnectionCustomizer connectionCustomizer : connectionCustomizers)
		{
			try
			{
				connectionCustomizer.onRelease(connection, getUniqueName());
			}
			catch (Exception ex)
			{
				log.log(Level.WARNING, "ConnectionCustomizer.onRelease() failed for " + connectionCustomizer, ex);
			}
		}
	}

	/**
	 * Method fireOnDestroy ...
	 *
	 * @param connection
	 * 		of type Connection
	 */
	void fireOnDestroy(Connection connection)
	{
		for (ConnectionCustomizer connectionCustomizer : connectionCustomizers)
		{
			try
			{
				connectionCustomizer.onDestroy(connection, getUniqueName());
			}
			catch (Exception ex)
			{
				log.log(Level.WARNING, "ConnectionCustomizer.onDestroy() failed for " + connectionCustomizer, ex);
			}
		}
	}

	/**
	 * Method toString ...
	 *
	 * @return String
	 */
	@Override
	public String toString()
	{
		return "a PoolingDataSource containing " + pool;
	}

	/**
	 * Prepare the recoverable {@link javax.transaction.xa.XAResource} producer for recovery.
	 *
	 * @return a {@link bitronix.tm.internal.XAResourceHolderState} object that can be used to call <code>recover()</code>.
	 *
	 * @throws bitronix.tm.recovery.RecoveryException
	 * 		thrown when a {@link bitronix.tm.internal.XAResourceHolderState} cannot be acquired.
	 */
	/* XAResourceProducer implementation */
	@Override
	public XAResourceHolderState startRecovery() throws RecoveryException
	{
		init();
		if (recoveryConnectionHandle != null)
		{
			throw new RecoveryException("recovery already in progress on " + this);
		}

		try
		{
			recoveryConnectionHandle = (Connection) pool.getConnectionHandle(false);
			PooledConnectionProxy pooledConnection = (PooledConnectionProxy) recoveryConnectionHandle;
			recoveryXAResourceHolder = pooledConnection.getPooledConnection()
			                                           .createRecoveryXAResourceHolder();
			return new XAResourceHolderState(pooledConnection.getPooledConnection(), this);
		}
		catch (Exception ex)
		{
			throw new RecoveryException("cannot start recovery on " + this, ex);
		}
	}

	/**
	 * Release internal resources held after call to <code>startRecovery()</code>.
	 *
	 * @throws bitronix.tm.recovery.RecoveryException
	 * 		thrown when an error occurred while releasing reserved resources.
	 */
	@Override
	public void endRecovery() throws RecoveryException
	{
		if (recoveryConnectionHandle == null)
		{
			return;
		}

		try
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("recovery xa resource is being closed: " + recoveryXAResourceHolder);
			}
			recoveryConnectionHandle.close();
		}
		catch (Exception ex)
		{
			throw new RecoveryException("error ending recovery on " + this, ex);
		}
		finally
		{
			recoveryConnectionHandle = null;

			// the recoveryXAResourceHolder actually wraps the recoveryConnectionHandle so closing it
			// would close the recoveryConnectionHandle twice which must not happen
			recoveryXAResourceHolder = null;
		}
	}    /* Implementation of DataSource interface */

	/**
	 * Method buildXAPool ...
	 *
	 * @throws Exception
	 * 		when
	 */
	private void buildXAPool() throws Exception
	{
		if (pool != null)
		{
			return;
		}

		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("building XA pool for " + getUniqueName() + " with " + getMinPoolSize() + " connection(s)");
		}
		pool = new XAPool<>(this, this, xaDataSource);
		boolean builtXaFactory = false;
		if (xaDataSource == null)
		{
			xaDataSource = (XADataSource) pool.getXAFactory();
			builtXaFactory = true;
		}
		try
		{
			ResourceRegistrar.register(this);
		}
		catch (RecoveryException ex)
		{
			if (builtXaFactory)
			{
				xaDataSource = null;
			}
			pool = null;
			throw ex;
		}
	}

	/**
	 * Method unwrap ...
	 *
	 * @param iface
	 * 		of type Class T
	 *
	 * @return T
	 *
	 * @throws SQLException
	 * 		when
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException
	{
		if (isWrapperFor(iface))
		{
			return (T) xaDataSource;
		}
		throw new SQLException(getClass().getName() + " is not a wrapper for " + iface);
	}

	/**
	 * Method isWrapperFor ...
	 *
	 * @param iface
	 * 		of type Class ?
	 *
	 * @return boolean
	 *
	 * @throws SQLException
	 * 		when
	 */
	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException
	{
		return iface.isAssignableFrom(xaDataSource.getClass());
	}

	/**
	 * Method getInPoolSize returns the inPoolSize of this PoolingDataSource object.
	 *
	 * @return the inPoolSize (type int) of this PoolingDataSource object.
	 */
	@Override
	public int getInPoolSize()
	{
		return pool.inPoolSize();
	}

	/**
	 * Method getTotalPoolSize returns the totalPoolSize of this PoolingDataSource object.
	 *
	 * @return the totalPoolSize (type int) of this PoolingDataSource object.
	 */
	@Override
	public int getTotalPoolSize()
	{
		return pool.totalPoolSize();
	}

	/**
	 * Method isFailed returns the failed of this PoolingDataSource object.
	 *
	 * @return the failed (type boolean) of this PoolingDataSource object.
	 */
	@Override
	public boolean isFailed()
	{
		return (pool != null ? pool.isFailed() : false);
	}

	/**
	 * Mark this resource producer as failed or not. A resource is considered failed if recovery fails to run on it.
	 *
	 * @param failed
	 * 		true is the resource must be considered failed, false it it must be considered sane.
	 */
	@Override
	public void setFailed(boolean failed)
	{
		if (pool != null)
		{
			pool.setFailed(failed);
		}
	}

	/**
	 * Find in the {@link bitronix.tm.resource.common.XAResourceHolder}s created by this {@link bitronix.tm.resource.common.XAResourceProducer} the one which this
	 * {@link javax.transaction.xa.XAResource} belongs to.
	 *
	 * @param xaResource
	 * 		the {@link javax.transaction.xa.XAResource} to look for.
	 *
	 * @return the associated {@link bitronix.tm.resource.common.XAResourceHolder} or null if the {@link javax.transaction.xa.XAResource} does not belong to this
	 * 		{@link bitronix.tm.resource.common.XAResourceProducer}.
	 */
	@Override
	public JdbcPooledConnection findXAResourceHolder(XAResource xaResource)
	{
		return xaResourceHolderMap.get(xaResource);
	}

	/**
	 * Initializes the pool by creating the initial amount of connections.
	 */
	@Override
	public synchronized void init()
	{
		if (pool != null)
		{
			return;
		}

		try
		{
			buildXAPool();
			jmxName = "bitronix.tm:type=JDBC,UniqueName=" + ManagementRegistrar.makeValidName(getUniqueName());
			ManagementRegistrar.register(jmxName, this);
		}
		catch (Exception ex)
		{
			throw new ResourceConfigurationException("cannot create JDBC datasource named " + getUniqueName(), ex);
		}
	}

	/**
	 * Release this {@link bitronix.tm.resource.common.XAResourceProducer}'s internal resources.
	 */
	@Override
	public void close()
	{
		if (pool == null)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("trying to close already closed PoolingDataSource " + getUniqueName());
			}
			return;
		}

		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("closing " + this);
		}
		pool.close();
		pool = null;

		xaResourceHolderMap.clear();

		connectionCustomizers.clear();

		ManagementRegistrar.unregister(jmxName);
		jmxName = null;

		ResourceRegistrar.unregister(this);
	}

	/**
	 * Create a {@link bitronix.tm.resource.common.XAStatefulHolder} that will be placed in an {@link bitronix.tm.resource.common.XAPool}.
	 *
	 * @param xaFactory
	 * 		the vendor's resource-specific XA factory.
	 * @param bean
	 * 		the resource-specific bean describing the resource parameters.
	 *
	 * @return a {@link bitronix.tm.resource.common.XAStatefulHolder} that will be placed in an {@link bitronix.tm.resource.common.XAPool}.
	 *
	 * @throws Exception
	 * 		thrown when the {@link bitronix.tm.resource.common.XAStatefulHolder} cannot be created.
	 */
	@Override
	public JdbcPooledConnection createPooledConnection(Object xaFactory, ResourceBean bean) throws Exception
	{
		if (!(xaFactory instanceof XADataSource))
		{
			throw new IllegalArgumentException("class '" + xaFactory.getClass()
			                                                        .getName() + "' does not implement " + XADataSource.class.getName());
		}
		XADataSource xads = (XADataSource) xaFactory;
		JdbcPooledConnection pooledConnection = new JdbcPooledConnection(this, xads.getXAConnection());
		xaResourceHolderMap.put(pooledConnection.getXAResource(), pooledConnection);
		return pooledConnection;
	}

	/**
	 * Method reset ...
	 *
	 * @throws Exception
	 * 		when
	 */
	@Override
	public void reset() throws Exception
	{
		pool.reset();
	}

	/**
	 * {@link PoolingDataSource} must alway have a unique name so this method builds a reference to this object using
	 * the unique name as {@link javax.naming.RefAddr}.
	 *
	 * @return a reference to this {@link PoolingDataSource}.
	 */
	@Override
	public Reference getReference() throws NamingException
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("creating new JNDI reference of " + this);
		}
		return new Reference(
				PoolingDataSource.class.getName(),
				new StringRefAddr("uniqueName", getUniqueName()),
				ResourceObjectFactory.class.getName(),
				null);
	}

	/**
	 * Method unregister ...
	 *
	 * @param xaResourceHolder
	 * 		of type JdbcPooledConnection
	 */
	public void unregister(JdbcPooledConnection xaResourceHolder)
	{
		xaResourceHolderMap.remove(xaResourceHolder.getXAResource());

	}

	/**
	 * Method getParentLogger returns the parentLogger of this PoolingDataSource object.
	 *
	 * @return the parentLogger (type Logger) of this PoolingDataSource object.
	 *
	 * @throws SQLFeatureNotSupportedException
	 * 		when
	 */
	@Override
	public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
	{
		throw new SQLFeatureNotSupportedException();
	}

	/**
	 * Method getConnection returns the connection of this PoolingDataSource object.
	 *
	 * @return the connection (type Connection) of this PoolingDataSource object.
	 *
	 * @throws SQLException
	 * 		when
	 */
	@Override
	public Connection getConnection() throws SQLException
	{
		if (isDisabled())
		{
			throw new SQLException("JDBC connection pool '" + getUniqueName() + "' is disabled, cannot get a connection from it");
		}

		init();
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("acquiring connection from " + this);
		}
		if (pool == null)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("pool is closed, returning null connection");
			}
			return null;
		}

		try
		{
			Connection conn = (Connection) pool.getConnectionHandle();
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("acquired connection from " + this);
			}
			return conn;
		}
		catch (Exception ex)
		{
			throw new SQLException("unable to get a connection from pool of " + this, ex);
		}
	}


	/**
	 * Method getConnection ...
	 *
	 * @param username
	 * 		of type String
	 * @param password
	 * 		of type String
	 *
	 * @return Connection
	 *
	 * @throws SQLException
	 * 		when
	 */
	@Override
	public Connection getConnection(String username, String password) throws SQLException
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("JDBC connections are pooled, username and password ignored");
		}
		return getConnection();
	}


	/* DataSource implementation */


	/**
	 * Method getLoginTimeout returns the loginTimeout of this PoolingDataSource object.
	 *
	 * @return the loginTimeout (type int) of this PoolingDataSource object.
	 *
	 * @throws SQLException
	 * 		when
	 */
	@Override
	public int getLoginTimeout() throws SQLException
	{
		return xaDataSource.getLoginTimeout();
	}


	/**
	 * Method setLoginTimeout sets the loginTimeout of this PoolingDataSource object.
	 *
	 * @param seconds
	 * 		the loginTimeout of this PoolingDataSource object.
	 *
	 * @throws SQLException
	 * 		when
	 */
	@Override
	public void setLoginTimeout(int seconds) throws SQLException
	{
		xaDataSource.setLoginTimeout(seconds);
	}


	/**
	 * Method getLogWriter returns the logWriter of this PoolingDataSource object.
	 *
	 * @return the logWriter (type PrintWriter) of this PoolingDataSource object.
	 *
	 * @throws SQLException
	 * 		when
	 */
	@Override
	public PrintWriter getLogWriter() throws SQLException
	{
		return xaDataSource.getLogWriter();
	}


	/**
	 * Method setLogWriter sets the logWriter of this PoolingDataSource object.
	 *
	 * @param out
	 * 		the logWriter of this PoolingDataSource object.
	 *
	 * @throws SQLException
	 * 		when
	 */
	@Override
	public void setLogWriter(PrintWriter out) throws SQLException
	{
		xaDataSource.setLogWriter(out);
	}

}
