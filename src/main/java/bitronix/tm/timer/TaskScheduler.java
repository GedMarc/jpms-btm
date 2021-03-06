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
package bitronix.tm.timer;

import bitronix.tm.BitronixTransaction;
import bitronix.tm.TransactionManagerServices;
import bitronix.tm.internal.LogDebugCheck;
import bitronix.tm.recovery.Recoverer;
import bitronix.tm.resource.common.XAPool;
import bitronix.tm.utils.MonotonicClock;
import bitronix.tm.utils.Service;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Timed tasks service.
 *
 * @author Ludovic Orban
 */
public class TaskScheduler
		extends Thread
		implements Service
{

	private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(TaskScheduler.class.toString());
	private static final String EXPECTED_EXECUTION_DATE = "expected a non-null execution date";
	private static final String TOTAL_QUEUED = ", total task(s) queued: ";
	private static final String SCHEDULED_STRING = "scheduled ";
	private static final String NO_TASK = "no task found based on object ";


	private final SortedSet<Task> tasks;
	private final Lock tasksLock;
	private final AtomicBoolean active = new AtomicBoolean(true);

	/**
	 * Constructor TaskScheduler creates a new TaskScheduler instance.
	 */
	public TaskScheduler()
	{
		// it is up to the ShutdownHandler to control the lifespan of the JVM and give some time for this thread
		// to die gracefully, meaning enough time for all tasks to get executed. This is why it is set as daemon.
		setDaemon(true);
		setName("bitronix-task-scheduler");

		SortedSet<Task> sortedTasks;
		Lock innerTasksLock;
		try
		{
			sortedTasks = new ConcurrentSkipListSet<>();
			innerTasksLock = null;
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("task scheduler backed by ConcurrentSkipListSet");
			}
		}
		catch (Exception e)
		{
			sortedTasks = new TreeSet<>();
			innerTasksLock = new ReentrantLock();
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("task scheduler backed by locked TreeSet");
				log.log(Level.FINEST, "exception is", e);
			}
		}
		this.tasks = sortedTasks;
		this.tasksLock = innerTasksLock;
	}

	/**
	 * Shutdown the service and free all held resources.
	 */
	@Override
	public void shutdown()
	{
		boolean wasActive = setActive(false);

		if (wasActive)
		{
			try
			{
				long gracefulShutdownTime = TransactionManagerServices.getConfiguration()
				                                                      .getGracefulShutdownInterval() * 1000L;
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("graceful scheduler shutdown interval: " + gracefulShutdownTime + "ms");
				}
				join(gracefulShutdownTime);
			}
			catch (InterruptedException ex)
			{
				log.log(Level.SEVERE, "could not stop the task scheduler within " + TransactionManagerServices.getConfiguration()
				                                                                                              .getGracefulShutdownInterval() + "s", ex);
			}
		}
	}

	/**
	 * Method setActive ...
	 *
	 * @param active
	 * 		of type boolean
	 *
	 * @return boolean
	 */
	boolean setActive(boolean active)
	{
		return this.active.getAndSet(active);
	}

	/**
	 * Schedule a task that will mark the transaction as timed out at the specified date. If this method is called
	 * with the same transaction multiple times, the previous timeout date is dropped and replaced by the new one.
	 *
	 * @param transaction
	 * 		the transaction to mark as timeout.
	 * @param executionTime
	 * 		the date at which the transaction must be marked.
	 */
	public void scheduleTransactionTimeout(BitronixTransaction transaction, Date executionTime)
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("scheduling transaction timeout task on " + transaction + " for " + executionTime);
		}
		if (transaction == null)
		{
			throw new IllegalArgumentException("expected a non-null transaction");
		}
		if (executionTime == null)
		{
			throw new IllegalArgumentException(EXPECTED_EXECUTION_DATE);
		}

		TransactionTimeoutTask task = new TransactionTimeoutTask(transaction, executionTime, this);
		addTask(task);
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer(SCHEDULED_STRING + task + TOTAL_QUEUED + countTasksQueued());
		}
	}

	/**
	 * Method addTask ...
	 *
	 * @param task
	 * 		of type Task
	 */
	void addTask(Task task)
	{
		lock();
		try
		{
			removeTaskByObject(task.getObject());
			tasks.add(task);
		}
		finally
		{
			unlock();
		}
	}

	/**
	 * Get the amount of tasks currently queued.
	 *
	 * @return the amount of tasks currently queued.
	 */
	public int countTasksQueued()
	{
		lock();
		try
		{
			return tasks.size();
		}
		finally
		{
			unlock();
		}
	}

	/**
	 * Method lock ...
	 */
	private void lock()
	{
		if (tasksLock != null)
		{
			tasksLock.lock();
		}
	}

	/**
	 * Method removeTaskByObject ...
	 *
	 * @param obj
	 * 		of type Object
	 *
	 * @return boolean
	 */
	boolean removeTaskByObject(Object obj)
	{
		lock();
		try
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("removing task by " + obj);
			}

			for (Task task : tasks)
			{
				if (task.getObject() == obj)
				{
					tasks.remove(task);
					if (LogDebugCheck.isDebugEnabled())
					{
						log.finer("cancelled " + task + ", total task(s) still queued: " + tasks.size());
					}
					return true;
				}
			}
			return false;
		}
		finally
		{
			unlock();
		}
	}

	/**
	 * Method unlock ...
	 */
	private void unlock()
	{
		if (tasksLock != null)
		{
			tasksLock.unlock();
		}
	}

	/**
	 * Cancel the task that will mark the transaction as timed out at the specified date.
	 *
	 * @param transaction
	 * 		the transaction to mark as timeout.
	 */
	public void cancelTransactionTimeout(BitronixTransaction transaction)
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("cancelling transaction timeout task on " + transaction);
		}
		if (transaction == null)
		{
			throw new IllegalArgumentException("expected a non-null transaction");
		}
		if (!removeTaskByObject(transaction) && LogDebugCheck.isDebugEnabled())
		{
			log.finer(NO_TASK + transaction);
		}

	}

	/**
	 * Schedule a task that will run background recovery at the specified date.
	 *
	 * @param recoverer
	 * 		the recovery implementation to use.
	 * @param executionTime
	 * 		the date at which the transaction must be marked.
	 */
	public void scheduleRecovery(Recoverer recoverer, Date executionTime)
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("scheduling recovery task for " + executionTime);
		}
		if (recoverer == null)
		{
			throw new IllegalArgumentException("expected a non-null recoverer");
		}
		if (executionTime == null)
		{
			throw new IllegalArgumentException(EXPECTED_EXECUTION_DATE);
		}

		RecoveryTask task = new RecoveryTask(recoverer, executionTime, this);
		addTask(task);
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer(SCHEDULED_STRING + task + TOTAL_QUEUED + countTasksQueued());
		}
	}

	/**
	 * Cancel the task that will run background recovery at the specified date.
	 *
	 * @param recoverer
	 * 		the recovery implementation to use.
	 */
	public void cancelRecovery(Recoverer recoverer)
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("cancelling recovery task");
		}
		if (!removeTaskByObject(recoverer) && LogDebugCheck.isDebugEnabled())
		{
			log.finer(NO_TASK + recoverer);
		}

	}

	/**
	 * Schedule a task that will tell a XA pool to close idle connections. The execution time will be provided by the
	 * XA pool itself via the {@link bitronix.tm.resource.common.XAPool#getNextShrinkDate()}.
	 *
	 * @param xaPool
	 * 		the XA pool to notify.
	 */
	public void schedulePoolShrinking(XAPool xaPool)
	{
		Date executionTime = xaPool.getNextShrinkDate();
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("scheduling pool shrinking task on " + xaPool + " for " + executionTime);
		}
		if (executionTime == null)
		{
			throw new IllegalArgumentException(EXPECTED_EXECUTION_DATE);
		}

		PoolShrinkingTask task = new PoolShrinkingTask(xaPool, executionTime, this);
		addTask(task);
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer(SCHEDULED_STRING + task + TOTAL_QUEUED + tasks.size());
		}
	}

	/**
	 * Cancel the task that will tell a XA pool to close idle connections.
	 *
	 * @param xaPool
	 * 		the XA pool to notify.
	 */
	public void cancelPoolShrinking(XAPool xaPool)
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("cancelling pool shrinking task on " + xaPool);
		}
		if (xaPool == null)
		{
			throw new IllegalArgumentException("expected a non-null XA pool");
		}

		if (!removeTaskByObject(xaPool) && LogDebugCheck.isDebugEnabled())
		{
			log.finer(NO_TASK + xaPool);
		}

	}

	/**
	 * Method run ...
	 */
	@Override
	public void run()
	{
		while (isActive())
		{
			try
			{
				executeElapsedTasks();
				Thread.sleep(500); // execute twice per second. That's enough precision.
			}
			catch (InterruptedException ex)
			{
				// ignore
			}
		}
	}

	/**
	 * Method isActive returns the active of this TaskScheduler object.
	 *
	 * @return the active (type boolean) of this TaskScheduler object.
	 */
	private boolean isActive()
	{
		return active.get();
	}

	/**
	 * Method executeElapsedTasks ...
	 */
	private void executeElapsedTasks()
	{
		lock();
		try
		{
			if (this.tasks.isEmpty())
			{
				return;
			}

			Set<Task> toRemove = new HashSet<>();
			for (Task task : getSafeIterableTasks())
			{
				if (task.getExecutionTime()
				        .compareTo(new Date(MonotonicClock.currentTimeMillis())) <= 0)
				{
					// if the execution time is now or in the past
					if (LogDebugCheck.isDebugEnabled())
					{
						log.finer("running " + task);
					}
					try
					{
						task.execute();
						if (LogDebugCheck.isDebugEnabled())
						{
							log.finer("successfully ran " + task);
						}
					}
					catch (Exception ex)
					{
						log.log(Level.WARNING, "error running " + task, ex);
					}
					finally
					{
						toRemove.add(task);
						if (LogDebugCheck.isDebugEnabled())
						{
							log.finer("total task(s) still queued: " + tasks.size());
						}
					}
				} // if
			}
			this.tasks.removeAll(toRemove);
		}
		finally
		{
			unlock();
		}
	}

	/**
	 * Method getSafeIterableTasks returns the safeIterableTasks of this TaskScheduler object.
	 *
	 * @return the safeIterableTasks (type SortedSet<Task>) of this TaskScheduler object.
	 */
	private SortedSet<Task> getSafeIterableTasks()
	{
		if (tasksLock != null)
		{
			return new TreeSet<>(tasks);
		}
		else
		{
			return tasks;
		}
	}

}
