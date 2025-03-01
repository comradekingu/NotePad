/*
 * Copyright (c) 2015 Jonas Kalderstam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nononsenseapps.notepad.sync.googleapi;

import android.database.Cursor;

import com.nononsenseapps.helpers.NnnLogger;
import com.nononsenseapps.notepad.database.RemoteTaskList;
import com.nononsenseapps.notepad.database.TaskList;
import com.nononsenseapps.helpers.RFC3339Date;

public class GoogleTaskList extends RemoteTaskList {

	public static final String SERVICENAME = "googletasks";
	public String title = null;
	public boolean remotelyDeleted = false;

	/**
	 * Intended for when default list is deleted. When that fails, redownload it and its contents
	 */
	public boolean redownload = false;

	public GoogleTaskList(GoogleTasksAPI.TaskListResource taskListResource, String accountName) {
		super();
		this.service = SERVICENAME;
		account = accountName;

		updateFromTaskListResource(taskListResource);
	}

	public GoogleTaskList(final TaskList dbList, final String accountName) {
		super();
		this.title = dbList.title;
		this.dbid = dbList._id;
		this.account = accountName;
		this.service = SERVICENAME;
	}

	public GoogleTaskList(final String accountName) {
		super();
		this.account = accountName;
		this.service = SERVICENAME;
	}

	public GoogleTaskList(final Cursor c) {
		super(c);
		this.service = SERVICENAME;
	}

	public GoogleTaskList(final Long dbid, final String remoteId, final Long updated, final String account) {
		super(dbid, remoteId, updated, account);
		this.service = SERVICENAME;
	}

	/**
	 * Includes title and not id
	 */
	public GoogleTasksAPI.TaskListResource toTaskListResource() {
		GoogleTasksAPI.TaskListResource taskListResource = new GoogleTasksAPI.TaskListResource();

		taskListResource.title = title;

		return taskListResource;
	}

	/**
	 * Update all fields from the resource
	 */
	public void updateFromTaskListResource(GoogleTasksAPI.TaskListResource taskListResource) {
		remoteId = taskListResource.id;
		title = taskListResource.title;

		try {
			updated = RFC3339Date.parseRFC3339Date(taskListResource.updated).getTime();
		} catch (Exception e) {
			NnnLogger.exception(e);
			updated = 0L;
		}
	}

	/**
	 * Returns true if the TaskList has the same remote id or the same database
	 * id.
	 */
	@Override
	public boolean equals(Object o) {
		boolean equal = false;
		if (o instanceof GoogleTaskList) {
			// It's a list!
			GoogleTaskList list = (GoogleTaskList) o;
			if (dbid != -1 && dbid.equals(list.dbid)) {
				equal = true;
			}
			if (remoteId != null && remoteId.equals(list.remoteId)) {
				equal = true;
			}
		}
		return equal;
	}
}
