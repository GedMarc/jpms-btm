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

import bitronix.tm.internal.BitronixRollbackSystemException;
import bitronix.tm.internal.BitronixSystemException;
import bitronix.tm.internal.LogDebugCheck;
import bitronix.tm.resource.common.*;
import bitronix.tm.resource.jdbc.LruStatementCache.CacheKey;
import bitronix.tm.resource.jdbc.lrc.LrcXADataSource;
import bitronix.tm.resource.jdbc.proxy.JdbcProxyFactory;
import bitronix.tm.utils.ManagementRegistrar;
import bitronix.tm.utils.MonotonicClock;
import bitronix.tm.utils.Scheduler;

import javax.sql.XAConnection;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.logging.Level;

/**
 * Implementation of a JDBC pooled connection wrapping vendor's {@link XAConnection} implementation.
 *
 * @author Ludovic Orban
 * @author Brett Wooldridge
 */
public class JdbcPooledConnection
		extends AbstractXAResourceHolder<JdbcPooledConnection>
		implements StateChangeListener<JdbcPooledConnection>, JdbcPooledConnectionMBean
{

	private final static java.util.logging.Logger log = java.util.logging.Logger.getLogger(JdbcPooledConnection.class.toString());

	private final XAConnection xaConnection;
	private final Connection connection;
	private final XAResource xaResource;
	private final PoolingDataSource poolingDataSource;
	private final LruStatementCache statementsCache;
	private final List<Statement> uncachedStatements;
	/* management */
	private final String jmxName;
	private volatile int usageCount;
	private volatile Date acquisitionDate;
	private volatile Date lastReleaseDate;

	private volatile int jdbcVersionDetected;

	public JdbcPooledConnection(PoolingDataSource poolingDataSource, XAConnection xaConnection) throws SQLException
	{
		this.poolingDataSource = poolingDataSource;
		this.xaConnection = xaConnection;
		this.xaResource = xaConnection.getXAResource();
		this.statementsCache = new LruStatementCache(poolingDataSource.getPreparedStatementCacheSize());
		this.uncachedStatements = Collections.synchronizedList(new ArrayList<>());
		this.lastReleaseDate = new Date(MonotonicClock.currentTimeMillis());
		statementsCache.addEvictionListener(stmt ->
		                                    {
			                                    try
			                                    {
				                                    stmt.close();
			                                    }
			                                    catch (SQLException ex)
			                                    {
				                                    log.log(Level.WARNING, "error closing evicted statement", ex);
			                                    }
		                                    });

		connection = xaConnection.getConnection();
		jdbcVersionDetected = JdbcClassHelper.detectJdbcVersion(connection);
		addStateChangeEventListener(this);

		if (LrcXADataSource.class.getName()
		                         .equals(poolingDataSource.getClassName()))
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("emulating XA for resource " + poolingDataSource.getUniqueName() + " - changing twoPcOrderingPosition to ALWAYS_LAST_POSITION");
			}
			poolingDataSource.setTwoPcOrderingPosition(Scheduler.ALWAYS_LAST_POSITION);
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("emulating XA for resource " + poolingDataSource.getUniqueName() + " - changing deferConnectionRelease to true");
			}
			poolingDataSource.setDeferConnectionRelease(true);
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("emulating XA for resource " + poolingDataSource.getUniqueName() + " - changing useTmJoin to true");
			}
			poolingDataSource.setUseTmJoin(true);
		}

		this.jmxName = "bitronix.tm:type=JDBC,UniqueName=" + ManagementRegistrar.makeValidName(poolingDataSource.getUniqueName()) + ",Id=" +
		               poolingDataSource.incCreatedResourcesCounter();
		ManagementRegistrar.register(jmxName, this);

		poolingDataSource.fireOnAcquire(connection);
	}

	public RecoveryXAResourceHolder createRecoveryXAResourceHolder()
	{
		return new RecoveryXAResourceHolder(this);
	}

	public boolean release() throws SQLException
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("releasing to pool " + this);
		}
		--usageCount;

		// delisting
		try
		{
			TransactionContextHelper.delistFromCurrentTransaction(this);
		}
		catch (BitronixRollbackSystemException ex)
		{
			throw new SQLException("unilateral rollback of " + this, ex);
		}
		catch (SystemException ex)
		{
			throw new SQLException("error delisting " + this, ex);
		}
		finally
		{
			// Only requeue a connection if it is no longer in use.  In the case of non-shared connections,
			// usageCount will always be 0 here, so the default behavior is unchanged.
			if (usageCount == 0)
			{
				poolingDataSource.fireOnRelease(connection);
				try
				{
					TransactionContextHelper.requeue(this, poolingDataSource);
				}
				catch (BitronixSystemException ex)
				{
					// Requeue failed, restore the usageCount to previous value (see testcase
					// NewJdbcStrangeUsageMockTest.testClosingSuspendedConnectionsInDifferentContext).
					// This can happen when a close is attempted while the connection is participating
					// in a global transaction.
					usageCount++;

					// this may hide the exception thrown by delistFromCurrentTransaction() but
					// an error requeuing must absolutely be reported as an exception.
					// Too bad if this happens... See DualSessionWrapper.close() as well.
					throw new SQLException("error requeuing " + this, ex);
				}

				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("released to pool " + this);
				}
			}
			else
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("not releasing " + this + " to pool yet, connection is still shared");
				}
			}
		} // finally

		return usageCount == 0;
	}

	@Override
	public XAResource getXAResource()
	{
		return xaResource;
	}

	@Override
	public ResourceBean getResourceBean()
	{
		return getPoolingDataSource();
	}

	public PoolingDataSource getPoolingDataSource()
	{
		return poolingDataSource;
	}

	@Override
	public List<JdbcPooledConnection> getXAResourceHolders()
	{
		return Collections.singletonList(this);
	}

	@Override
	public Object getConnectionHandle() throws Exception
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("getting connection handle from " + this);
		}
		State oldState = getState();

		// Increment the usage count
		usageCount++;

		// Only transition to State.ACCESSIBLE on the first usage.  If we're not sharing
		// connections (default behavior) usageCount is always 1 here, so this transition
		// will always occur (current behavior unchanged).  If we _are_ sharing connections,
		// and this is _not_ the first usage, it is valid for the state to already be
		// State.ACCESSIBLE.  Calling setState() with State.ACCESSIBLE when the state is
		// already State.ACCESSIBLE fails the sanity check in AbstractXAStatefulHolder.
		// Even if the connection is shared (usageCount > 1), if the state was State.NOT_ACCESSIBLE
		// we transition back to State.ACCESSIBLE.
		if (usageCount == 1 || oldState == State.NOT_ACCESSIBLE)
		{
			setState(State.ACCESSIBLE);
		}

		if (oldState == State.IN_POOL)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("connection " + xaConnection + " was in state IN_POOL, testing it");
			}
			testConnection(connection);
			applyIsolationLevel();
			applyCursorHoldabilty();
			if (TransactionContextHelper.currentTransaction() == null)
			{
				// it is safe to set the auto-commit flag outside of a global transaction
				applyLocalAutoCommit();
			}
		}
		else
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("connection " + xaConnection + " was in state " + oldState + ", no need to test it");
			}
		}

		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("got connection handle from " + this);
		}
		poolingDataSource.fireOnLease(connection);

		return getConnectionHandle(connection);
	}

	@Override
	public void close() throws SQLException
	{
		// this should never happen, should we throw an exception or log at warn/error?
		if (usageCount > 0)
		{
			log.warning("close connection with usage count > 0, " + this);
		}

		setState(State.CLOSED);

		// cleanup of pooled resources
		statementsCache.clear();

		ManagementRegistrar.unregister(jmxName);

		poolingDataSource.unregister(this);

		try
		{
			connection.close();
		}
		finally
		{
			try
			{
				xaConnection.close();
			}
			finally
			{
				poolingDataSource.fireOnDestroy(connection);
			}
		}
	}

	@Override
	public Date getLastReleaseDate()
	{
		return lastReleaseDate;
	}

	private void testConnection(Connection connection) throws SQLException
	{
		int connectionTestTimeout = poolingDataSource.getEffectiveConnectionTestTimeout();

		if (poolingDataSource.isEnableJdbc4ConnectionTest() && jdbcVersionDetected >= 4)
		{
			Boolean isValid = null;
			try
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("testing with JDBC4 isValid() method, connection of " + this);
				}
				Method isValidMethod = JdbcClassHelper.getIsValidMethod(connection);
				isValid = (Boolean) isValidMethod.invoke(connection, new Object[]{connectionTestTimeout});
			}
			catch (Exception e)
			{
				log.warning("dysfunctional JDBC4 Connection.isValid() method, or negative acquisition timeout, in call to test connection of " + this +
				            ".  Falling back to test query.");
				jdbcVersionDetected = 3;
			}
			// if isValid is null, an exception was caught above and we fall through to the query test
			if (isValid != null)
			{
				if (isValid.booleanValue())
				{
					if (LogDebugCheck.isDebugEnabled())
					{
						log.finer("isValid successfully tested connection of " + this);
					}
					return;
				}
				throw new SQLException("connection is no longer valid");
			}
		}

		String query = poolingDataSource.getTestQuery();
		if (query == null)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("no query to test connection of " + this + ", skipping test");
			}
			return;
		}

		// Throws a SQLException if the connection is dead
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("testing with query '" + query + "' connection of " + this);
		}
		PreparedStatement stmt = connection.prepareStatement(query);
		try
		{
			stmt.setQueryTimeout(connectionTestTimeout);
			ResultSet rs = stmt.executeQuery();
			rs.close();
		}
		finally
		{
			stmt.close();
		}
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("testQuery successfully tested connection of " + this);
		}
	}

	private void applyIsolationLevel() throws SQLException
	{
		String isolationLevel = getPoolingDataSource().getIsolationLevel();
		if (isolationLevel != null)
		{
			int level = translateIsolationLevel(isolationLevel);
			if (level < 0)
			{
				log.warning("invalid transaction isolation level '" + isolationLevel + "' configured, keeping the default isolation level.");
			}
			else
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("setting connection's isolation level to " + isolationLevel);
				}
				connection.setTransactionIsolation(level);
			}
		}
	}

	private void applyCursorHoldabilty() throws SQLException
	{
		String cursorHoldability = getPoolingDataSource().getCursorHoldability();
		if (cursorHoldability != null)
		{
			int holdability = translateCursorHoldability(cursorHoldability);
			if (holdability < 0)
			{
				log.warning("invalid cursor holdability '" + cursorHoldability + "' configured, keeping the default cursor holdability.");
			}
			else
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("setting connection's cursor holdability to " + cursorHoldability);
				}
				connection.setHoldability(holdability);
			}
		}
	}

	private void applyLocalAutoCommit() throws SQLException
	{
		String localAutoCommit = getPoolingDataSource().getLocalAutoCommit();
		if (localAutoCommit != null)
		{
			if (localAutoCommit.equalsIgnoreCase("true"))
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("setting connection's auto commit to true");
				}
				connection.setAutoCommit(true);
			}
			else if (localAutoCommit.equalsIgnoreCase("false"))
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("setting connection's auto commit to false");
				}
				connection.setAutoCommit(false);
			}
			else
			{
				log.warning("invalid auto commit '" + localAutoCommit + "' configured, keeping default auto commit");
			}
		}
	}

	private Object getConnectionHandle(Connection connection) throws SQLException
	{
		return JdbcProxyFactory.INSTANCE.getProxyConnection(this, connection);
	}

	private static int translateIsolationLevel(String isolationLevelGuarantee)
	{
		if ("READ_COMMITTED".equals(isolationLevelGuarantee))
		{
			return Connection.TRANSACTION_READ_COMMITTED;
		}
		if ("READ_UNCOMMITTED".equals(isolationLevelGuarantee))
		{
			return Connection.TRANSACTION_READ_UNCOMMITTED;
		}
		if ("REPEATABLE_READ".equals(isolationLevelGuarantee))
		{
			return Connection.TRANSACTION_REPEATABLE_READ;
		}
		if ("SERIALIZABLE".equals(isolationLevelGuarantee))
		{
			return Connection.TRANSACTION_SERIALIZABLE;
		}
		try
		{
			return Integer.parseInt(isolationLevelGuarantee);
		}
		catch (NumberFormatException ex)
		{
			// ignore
		}
		return -1;
	}

	private static int translateCursorHoldability(String cursorHoldability)
	{
		if ("CLOSE_CURSORS_AT_COMMIT".equals(cursorHoldability))
		{
			return ResultSet.CLOSE_CURSORS_AT_COMMIT;
		}
		if ("HOLD_CURSORS_OVER_COMMIT".equals(cursorHoldability))
		{
			return ResultSet.HOLD_CURSORS_OVER_COMMIT;
		}
		return -1;
	}

	public int getJdbcVersion()
	{
		return jdbcVersionDetected;
	}

	@Override
	public void stateChanged(JdbcPooledConnection source, State oldState, State newState)
	{
		if (newState == State.IN_POOL)
		{
			lastReleaseDate = new Date(MonotonicClock.currentTimeMillis());
		}
		else if (oldState == State.IN_POOL && newState == State.ACCESSIBLE)
		{
			acquisitionDate = new Date(MonotonicClock.currentTimeMillis());
		}
		else if (oldState == State.NOT_ACCESSIBLE && newState == State.ACCESSIBLE)
		{
			TransactionContextHelper.recycle(this);
		}
	}

	@Override
	public void stateChanging(JdbcPooledConnection source, State currentState, State futureState)
	{
		if (futureState == State.IN_POOL && usageCount > 0)
		{
			log.warning("usage count too high (" + usageCount + ") on connection returned to pool " + source);
		}

		if (futureState == State.IN_POOL || futureState == State.NOT_ACCESSIBLE)
		{
			// close all uncached statements
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("closing " + uncachedStatements.size() + " dangling uncached statement(s)");
			}
			for (Statement statement : uncachedStatements)
			{
				try
				{
					statement.close();
				}
				catch (SQLException ex)
				{
					if (LogDebugCheck.isDebugEnabled())
					{
						log.log(Level.FINER, "error trying to close uncached statement " + statement, ex);
					}
				}
			}
			uncachedStatements.clear();

			// clear SQL warnings
			try
			{
				connection.clearWarnings();
			}
			catch (SQLException ex)
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.log(Level.FINER, "error cleaning warnings of " + connection, ex);
				}
			}
		}
	}

	/**
	 * Get a PreparedStatement from cache.
	 *
	 * @param key
	 * 		the key that has been used to cache the statement.
	 *
	 * @return the cached statement corresponding to the key or null if no statement is cached under that key.
	 */
	public PreparedStatement getCachedStatement(CacheKey key)
	{
		return statementsCache.get(key);
	}

	/**
	 * Put a PreparedStatement in the cache.
	 *
	 * @param key
	 * 		the statement's cache key.
	 * @param statement
	 * 		the statement to cache.
	 *
	 * @return the cached statement.
	 */
	public PreparedStatement putCachedStatement(CacheKey key, PreparedStatement statement)
	{
		return statementsCache.put(key, statement);
	}

	/**
	 * Register uncached statement so that it can be closed when the connection is put back in the pool.
	 *
	 * @param stmt
	 * 		the statement to register.
	 *
	 * @return the registered statement.
	 */
	public Statement registerUncachedStatement(Statement stmt)
	{
		uncachedStatements.add(stmt);
		return stmt;
	}

	public void unregisterUncachedStatement(Statement stmt)
	{
		uncachedStatements.remove(stmt);
	}

	/* management */

	@Override
	public String toString()
	{
		return "a JdbcPooledConnection from datasource " + poolingDataSource.getUniqueName() + " in state " + getState() + " with usage count " + usageCount + " wrapping " +
		       xaConnection;
	}

	@Override
	public String getStateDescription()
	{
		return getState().toString();
	}

	@Override
	public Date getAcquisitionDate()
	{
		return acquisitionDate;
	}

	@Override
	public Collection<String> getTransactionGtridsCurrentlyHoldingThis()
	{
		return getXAResourceHolderStateGtrids();
	}
}
