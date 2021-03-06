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
package bitronix.tm.utils;

import bitronix.tm.internal.XAResourceHolderState;
import bitronix.tm.journal.TransactionLogHeader;

import jakarta.transaction.Status;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import java.util.Collection;
import java.util.Iterator;

/**
 * Constant to string decoder.
 *
 * @author Ludovic Orban
 */
public class Decoder
{

	private Decoder()
	{
		//Nothing
	}

	/**
	 * Method decodeXAExceptionErrorCode ...
	 *
	 * @param ex
	 * 		of type XAException
	 *
	 * @return String
	 */
	public static String decodeXAExceptionErrorCode(XAException ex)
	{
		switch (ex.errorCode)
		{
			// rollback errors
			case XAException.XA_RBROLLBACK:
				return "XA_RBROLLBACK";
			case XAException.XA_RBCOMMFAIL:
				return "XA_RBCOMMFAIL";
			case XAException.XA_RBDEADLOCK:
				return "XA_RBDEADLOCK";
			case XAException.XA_RBTRANSIENT:
				return "XA_RBTRANSIENT";
			case XAException.XA_RBINTEGRITY:
				return "XA_RBINTEGRITY";
			case XAException.XA_RBOTHER:
				return "XA_RBOTHER";
			case XAException.XA_RBPROTO:
				return "XA_RBPROTO";
			case XAException.XA_RBTIMEOUT:
				return "XA_RBTIMEOUT";

			// heuristic errors
			case XAException.XA_HEURCOM:
				return "XA_HEURCOM";
			case XAException.XA_HEURHAZ:
				return "XA_HEURHAZ";
			case XAException.XA_HEURMIX:
				return "XA_HEURMIX";
			case XAException.XA_HEURRB:
				return "XA_HEURRB";

			// misc failures errors
			case XAException.XAER_RMERR:
				return "XAER_RMERR";
			case XAException.XAER_RMFAIL:
				return "XAER_RMFAIL";
			case XAException.XAER_NOTA:
				return "XAER_NOTA";
			case XAException.XAER_INVAL:
				return "XAER_INVAL";
			case XAException.XAER_PROTO:
				return "XAER_PROTO";
			case XAException.XAER_ASYNC:
				return "XAER_ASYNC";
			case XAException.XAER_DUPID:
				return "XAER_DUPID";
			case XAException.XAER_OUTSIDE:
				return "XAER_OUTSIDE";

			default:
				return "!invalid error code (" + ex.errorCode + ")!";
		}
	}

	/**
	 * Method decodeStatus ...
	 *
	 * @param status
	 * 		of type int
	 *
	 * @return String
	 */
	public static String decodeStatus(int status)
	{
		switch (status)
		{
			case Status.STATUS_ACTIVE:
				return "ACTIVE";
			case Status.STATUS_COMMITTED:
				return "COMMITTED";
			case Status.STATUS_COMMITTING:
				return "COMMITTING";
			case Status.STATUS_MARKED_ROLLBACK:
				return "MARKED_ROLLBACK";
			case Status.STATUS_NO_TRANSACTION:
				return "NO_TRANSACTION";
			case Status.STATUS_PREPARED:
				return "PREPARED";
			case Status.STATUS_PREPARING:
				return "PREPARING";
			case Status.STATUS_ROLLEDBACK:
				return "ROLLEDBACK";
			case Status.STATUS_ROLLING_BACK:
				return "ROLLING_BACK";
			case Status.STATUS_UNKNOWN:
				return "UNKNOWN";
			default:
				return "!incorrect status (" + status + ")!";
		}
	}

	/**
	 * Method decodeXAResourceFlag ...
	 *
	 * @param flag
	 * 		of type int
	 *
	 * @return String
	 */
	public static String decodeXAResourceFlag(int flag)
	{
		switch (flag)
		{
			case XAResource.TMENDRSCAN:
				return "ENDRSCAN";
			case XAResource.TMFAIL:
				return "FAIL";
			case XAResource.TMJOIN:
				return "JOIN";
			case XAResource.TMNOFLAGS:
				return "NOFLAGS";
			case XAResource.TMONEPHASE:
				return "ONEPHASE";
			case XAResource.TMRESUME:
				return "RESUME";
			case XAResource.TMSTARTRSCAN:
				return "STARTRSCAN";
			case XAResource.TMSUCCESS:
				return "SUCCESS";
			case XAResource.TMSUSPEND:
				return "SUSPEND";
			default:
				return "!invalid flag (" + flag + ")!";
		}
	}

	/**
	 * Method decodePrepareVote ...
	 *
	 * @param vote
	 * 		of type int
	 *
	 * @return String
	 */
	public static String decodePrepareVote(int vote)
	{
		switch (vote)
		{
			case XAResource.XA_OK:
				return "XA_OK";
			case XAResource.XA_RDONLY:
				return "XA_RDONLY";
			default:
				return "!invalid return code (" + vote + ")!";
		}
	}

	/**
	 * Method decodeHeaderState ...
	 *
	 * @param state
	 * 		of type byte
	 *
	 * @return String
	 */
	public static String decodeHeaderState(byte state)
	{
		switch (state)
		{
			case TransactionLogHeader.CLEAN_LOG_STATE:
				return "CLEAN_LOG_STATE";
			case TransactionLogHeader.UNCLEAN_LOG_STATE:
				return "UNCLEAN_LOG_STATE";
			default:
				return "!invalid state (" + state + ")!";
		}
	}

	/**
	 * Create a String representation of a list of {@link bitronix.tm.resource.common.XAResourceHolder}s. This
	 * String will contain each resource's unique name.
	 *
	 * @param resources
	 * 		a list of {@link bitronix.tm.resource.common.XAResourceHolder}s.
	 *
	 * @return a String representation of the list.
	 */
	public static String collectResourcesNames(Collection<XAResourceHolderState> resources)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("[");

		Iterator<XAResourceHolderState> it = resources.iterator();
		while (it.hasNext())
		{
			XAResourceHolderState resourceHolderState = it.next();
			sb.append(resourceHolderState.getUniqueName());

			if (it.hasNext())
			{
				sb.append(", ");
			}
		}

		sb.append("]");
		return sb.toString();
	}

}
