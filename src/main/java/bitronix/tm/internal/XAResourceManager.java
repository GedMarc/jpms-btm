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
package bitronix.tm.internal;

import bitronix.tm.BitronixXid;
import bitronix.tm.TransactionManagerServices;
import bitronix.tm.resource.common.XAResourceHolder;
import bitronix.tm.utils.Scheduler;
import bitronix.tm.utils.Uid;
import bitronix.tm.utils.UidGenerator;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.util.*;

/**
 * Every {@link bitronix.tm.BitronixTransaction} contains an instance of this class that is used to register
 * and keep track of resources enlisted in a transaction.
 *
 * @author Ludovic Orban
 */
public class XAResourceManager
{

	private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(XAResourceManager.class.toString());

	private final Uid gtrid;
	private final Scheduler<XAResourceHolderState> resources = new Scheduler<>();

	/**
	 * Create a resource manager for the specified GTRID.
	 *
	 * @param gtrid
	 * 		the transaction's GTRID this XAResourceManager will be assigned to.
	 */
	public XAResourceManager(Uid gtrid)
	{
		this.gtrid = gtrid;
	}

	/**
	 * Delist the specified {@link XAResourceHolderState}. A reference to the resource is kept anyway.
	 *
	 * @param xaResourceHolderState
	 * 		the {@link XAResourceHolderState} to be delisted.
	 * @param flag
	 * 		the delistment flag.
	 *
	 * @return true if the resource could be delisted, false otherwise.
	 *
	 * @throws XAException
	 * 		if the resource threw an exception during delistment.
	 */
	public boolean delist(XAResourceHolderState xaResourceHolderState, int flag) throws XAException
	{
		if (findXAResourceHolderState(xaResourceHolderState.getXAResource()) != null)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("delisting resource " + xaResourceHolderState);
			}
			xaResourceHolderState.end(flag);
			return true;
		}

		log.warning("trying to delist resource that has not been previously enlisted: " + xaResourceHolderState);
		return false;
	}

	/**
	 * Look if an {@link XAResource} has already been enlisted.
	 *
	 * @param xaResource
	 * 		the {@link XAResource} to look for.
	 *
	 * @return the {@link XAResourceHolderState} of the enlisted resource or null if the {@link XAResource} has not
	 * 		been enlisted in this {@link XAResourceManager}.
	 */
	public XAResourceHolderState findXAResourceHolderState(XAResource xaResource)
	{
		for (XAResourceHolderState xaResourceHolderState : resources)
		{
			if (xaResourceHolderState.getXAResource() == xaResource)
			{
				return xaResourceHolderState;
			}
		}

		return null;
	}

	/**
	 * Suspend all enlisted resources from the current transaction context.
	 *
	 * @throws XAException
	 * 		if the resource threw an exception during suspend.
	 */
	public void suspend() throws XAException
	{
		for (XAResourceHolderState xaResourceHolderState : resources)
		{
			if (!xaResourceHolderState.isEnded())
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("suspending " + xaResourceHolderState);
				}
				xaResourceHolderState.end(XAResource.TMSUCCESS);
			}
		} // while
	}

	/**
	 * Resume all enlisted resources in the current transaction context.
	 *
	 * @throws XAException
	 * 		if the resource threw an exception during resume.
	 */
	public void resume() throws XAException
	{
		// all XAResource needs to be re-enlisted but this must happen
		// outside the Scheduler's iteration as enlist() can change the
		// collection's content and confuse the iterator.
		List<XAResourceHolderState> toBeReEnlisted = new ArrayList<>();

		for (XAResourceHolderState xaResourceHolderState : resources)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("resuming " + xaResourceHolderState);
			}

			// If a prepared statement is (re-)used after suspend/resume is performed its XAResource needs to be
			// re-enlisted. This must be done outside this loop or that will confuse the iterator!
			toBeReEnlisted.add(new XAResourceHolderState(xaResourceHolderState));
		}

		if (!toBeReEnlisted.isEmpty() && LogDebugCheck.isDebugEnabled())
		{
			log.finer("re-enlisting " + toBeReEnlisted.size() + " resource(s)");
		}
		for (XAResourceHolderState xaResourceHolderState : toBeReEnlisted)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("re-enlisting resource " + xaResourceHolderState);
			}
			try
			{
				enlist(xaResourceHolderState);
				xaResourceHolderState.getXAResourceHolder()
				                     .putXAResourceHolderState(xaResourceHolderState.getXid(), xaResourceHolderState);
			}
			catch (BitronixSystemException ex)
			{
				throw new BitronixXAException("error re-enlisting resource during resume: " + xaResourceHolderState, XAException.XAER_RMERR, ex);
			}
		}
	}

	/**
	 * Enlist the specified {@link XAResourceHolderState}. A XID is generated and the resource is started with
	 * XAResource.TMNOFLAGS or XAResource.TMJOIN if it could be joined with another previously enlisted one.
	 * <br>
	 * There are 3 different cases that can happen when a {@link XAResourceHolderState} is enlisted:
	 * <ul>
	 * <li>already enlisted and not ended: do nothing</li>
	 * <li>already enlisted and ended: try to join. if you can join, keep a reference on the passed-in
	 * {@link XAResourceHolderState} and drop the previous one. if you cannot join, it's the same as case 3</li>
	 * <li>not enlisted: create a new branch and keep a reference on the passed-in {@link XAResourceHolderState}</li>
	 * </ul>
	 *
	 * @param xaResourceHolderState
	 * 		the {@link XAResourceHolderState} to be enlisted.
	 *
	 * @throws XAException
	 * 		if a resource error occured.
	 * @throws BitronixSystemException
	 * 		if an internal error occured.
	 */
	public void enlist(XAResourceHolderState xaResourceHolderState) throws XAException, BitronixSystemException
	{
		XAResourceHolderState alreadyEnlistedHolder = findXAResourceHolderState(xaResourceHolderState.getXAResource());
		if (alreadyEnlistedHolder != null && !alreadyEnlistedHolder.isEnded())
		{
			xaResourceHolderState.setXid(alreadyEnlistedHolder.getXid());
			log.warning("ignoring enlistment of already enlisted but not ended resource " + xaResourceHolderState);
			return;
		}

		XAResourceHolderState toBeJoinedHolderState = null;
		if (alreadyEnlistedHolder != null)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("resource already enlisted but has been ended eligible for join: " + alreadyEnlistedHolder);
			}
			toBeJoinedHolderState = getManagedResourceWithSameRM(xaResourceHolderState);
		}

		BitronixXid xid;
		int flag;

		if (toBeJoinedHolderState != null)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("joining " + xaResourceHolderState + " with " + toBeJoinedHolderState);
			}
			xid = toBeJoinedHolderState.getXid();
			flag = XAResource.TMJOIN;
		}
		else
		{
			xid = UidGenerator.generateXid(gtrid);
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("creating new branch with " + xid);
			}
			flag = XAResource.TMNOFLAGS;
		}

		// check for enlistment of a 2nd LRC resource, forbid this if the 2nd resource cannot be joined with the 1st one
		// unless this is explicitly allowed in the config
		if (flag != XAResource.TMJOIN && xaResourceHolderState.getTwoPcOrderingPosition() == Scheduler.ALWAYS_LAST_POSITION &&
		    !TransactionManagerServices.getConfiguration()
		                               .isAllowMultipleLrc())
		{
			List<XAResourceHolderState> alwaysLastResources = resources.getByNaturalOrderForPosition(Scheduler.ALWAYS_LAST_POSITION);
			if (alwaysLastResources != null && !alwaysLastResources.isEmpty())
			{
				throw new BitronixSystemException(
						"cannot enlist more than one non-XA resource, tried enlisting " + xaResourceHolderState + ", already enlisted: " + alwaysLastResources.get(0));
			}
		}

		xaResourceHolderState.setXid(xid);
		xaResourceHolderState.start(flag);


		// in case of a JOIN, the resource holder is already in the scheduler -> do not add it twice
		if (toBeJoinedHolderState != null)
		{
			resources.remove(toBeJoinedHolderState);
		}
		// this must be done only after start() successfully returned
		resources.add(xaResourceHolderState, xaResourceHolderState.getTwoPcOrderingPosition());
	}

	/**
	 * Search for an eventually already enlisted {@link XAResourceHolderState} that could be joined with the
	 * {@link XAResourceHolderState} passed as parameter.<br/>
	 * If datasource configuration property <code>bitronix.useTmJoin=false</code> is set this method always returns null.
	 *
	 * @param xaResourceHolderState
	 * 		a {@link XAResourceHolderState} looking to be joined.
	 *
	 * @return another enlisted {@link XAResourceHolderState} that can be joined with the one passed in or null if none is found.
	 *
	 * @throws XAException
	 * 		if call to XAResource.isSameRM() fails.
	 */
	private XAResourceHolderState getManagedResourceWithSameRM(XAResourceHolderState xaResourceHolderState) throws XAException
	{
		if (!xaResourceHolderState.getUseTmJoin())
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("join disabled on resource " + xaResourceHolderState);
			}
			return null;
		}

		for (XAResourceHolderState alreadyEnlistedHolderState : resources)
		{
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("checking joinability of " + xaResourceHolderState + " with " + alreadyEnlistedHolderState);
			}
			if (alreadyEnlistedHolderState.isEnded() &&
			    !alreadyEnlistedHolderState.isSuspended() &&
			    xaResourceHolderState.getXAResource()
			                         .isSameRM(alreadyEnlistedHolderState.getXAResource()))
			{
				if (LogDebugCheck.isDebugEnabled())
				{
					log.finer("resources are joinable");
				}
				return alreadyEnlistedHolderState;
			}
			if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("resources are not joinable");
			}
		}

		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("no joinable resource found for " + xaResourceHolderState);
		}
		return null;
	}

	/**
	 * Remove this transaction's {@link XAResourceHolderState} from all enlisted
	 * {@link bitronix.tm.resource.common.XAResourceHolder}s.
	 */
	public void clearXAResourceHolderStates()
	{
		if (LogDebugCheck.isDebugEnabled())
		{
			log.finer("clearing XAResourceHolder states on " + resources.size() + " resource(s)");
		}
		Iterator<XAResourceHolderState> it = resources.iterator();
		while (it.hasNext())
		{
			XAResourceHolderState xaResourceHolderState = it.next();
			XAResourceHolder resourceHolder = xaResourceHolderState.getXAResourceHolder();

			// clear out the current state
			resourceHolder.removeXAResourceHolderState(xaResourceHolderState.getXid());

			boolean stillExists = resourceHolder.isExistXAResourceHolderStatesForGtrid(gtrid);
			if (stillExists)
			{
				log.warning("resource " + resourceHolder + " did not clean up " + resourceHolder.getXAResourceHolderStateCountForGtrid(gtrid) + "transaction states for GTRID [" +
				            gtrid + "]");
			}
			else if (LogDebugCheck.isDebugEnabled())
			{
				log.finer("resource " + resourceHolder + " cleaned up all transaction states for GTRID [" + gtrid + "]");
			}

			it.remove();
		}
	}

	/**
	 * Get a {@link Set} of unique names of all the enlisted {@link XAResourceHolderState}s.
	 *
	 * @return a {@link Set} of unique names of all the enlisted {@link XAResourceHolderState}s.
	 */
	public Set<String> collectUniqueNames()
	{
		Set<String> names = new HashSet<>(resources.size());
		for (XAResourceHolderState xaResourceHolderState : resources)
		{
			names.add(xaResourceHolderState.getUniqueName());
		}
		return Collections.unmodifiableSet(names);
	}

	/**
	 * Method getNaturalOrderPositions returns the naturalOrderPositions of this XAResourceManager object.
	 *
	 * @return the naturalOrderPositions (type SortedSetInteger) of this XAResourceManager object.
	 */
	public SortedSet<Integer> getNaturalOrderPositions()
	{
		return Collections.unmodifiableSortedSet(resources.getNaturalOrderPositions());
	}

	/**
	 * Method getReverseOrderPositions returns the reverseOrderPositions of this XAResourceManager object.
	 *
	 * @return the reverseOrderPositions (type SortedSetInteger) of this XAResourceManager object.
	 */
	public SortedSet<Integer> getReverseOrderPositions()
	{
		return Collections.unmodifiableSortedSet(resources.getReverseOrderPositions());
	}

	/**
	 * Method getNaturalOrderResourcesForPosition ...
	 *
	 * @param position
	 * 		of type Integer
	 *
	 * @return ListXAResourceHolderState
	 */
	public List<XAResourceHolderState> getNaturalOrderResourcesForPosition(Integer position)
	{
		return Collections.unmodifiableList(resources.getByNaturalOrderForPosition(position));
	}

	/**
	 * Method getReverseOrderResourcesForPosition ...
	 *
	 * @param position
	 * 		of type Integer
	 *
	 * @return List XAResourceHolderState
	 */
	public List<XAResourceHolderState> getReverseOrderResourcesForPosition(Integer position)
	{
		return Collections.unmodifiableList(resources.getByReverseOrderForPosition(position));
	}

	/**
	 * Method getAllResources returns the allResources of this XAResourceManager object.
	 *
	 * @return the allResources (type ListXAResourceHolderState) of this XAResourceManager object.
	 */
	public List<XAResourceHolderState> getAllResources()
	{
		List<XAResourceHolderState> result = new ArrayList<>(resources.size());
		for (Integer positionKey : resources.getNaturalOrderPositions())
		{
			result.addAll(resources.getByNaturalOrderForPosition(positionKey));
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * Get the enlisted resources count.
	 *
	 * @return the enlisted resources count.
	 */
	public int size()
	{
		return resources.size();
	}

	/**
	 * Get the GTRID of the transaction the {@link XAResourceManager} instance is attached to.
	 *
	 * @return the GTRID of the transaction the {@link XAResourceManager} instance is attached to.
	 */
	public Uid getGtrid()
	{
		return gtrid;
	}

	/**
	 * Return a human-readable representation of this object.
	 *
	 * @return a human-readable representation of this object.
	 */
	@Override
	public String toString()
	{
		return "a XAResourceManager with GTRID [" + gtrid + "] and " + resources;
	}

}
