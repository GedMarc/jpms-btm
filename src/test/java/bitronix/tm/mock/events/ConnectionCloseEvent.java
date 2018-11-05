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
package bitronix.tm.mock.events;

/**
 * @author Ludovic Orban
 */
public class ConnectionCloseEvent
		extends Event
{

	public ConnectionCloseEvent(Object source)
	{
		super(source, null);
	}

	public ConnectionCloseEvent(Object source, Exception ex)
	{
		super(source, ex);
	}

	@Override
	public String toString()
	{
		return "ConnectionCloseEvent at " + getTimestamp() + (getException() != null ? " and " + getException().toString() : "");
	}
}