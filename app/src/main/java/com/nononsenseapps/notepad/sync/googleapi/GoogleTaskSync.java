/*
 * Copyright (c) 2015. Jonas Kalderstam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nononsenseapps.notepad.sync.googleapi;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Pair;

import androidx.preference.PreferenceManager;

import com.nononsenseapps.build.Config;
import com.nononsenseapps.helpers.NnnLogger;
import com.nononsenseapps.notepad.database.Task;
import com.nononsenseapps.notepad.database.TaskList;
import com.nononsenseapps.notepad.prefs.SyncPrefs;
import com.nononsenseapps.helpers.PermissionsHelper;
import com.nononsenseapps.helpers.SyncGtaskHelper;
import com.nononsenseapps.helpers.RFC3339Date;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

public class GoogleTaskSync {

	public static final boolean NOTIFY_AUTH_FAILURE = true;
	public static final String PREFS_LAST_SYNC_ETAG = "lastserveretag";
	public static final String PREFS_GTASK_LAST_SYNC_TIME = "gtasklastsync";

	/**
	 * Returns true if sync was successful, false otherwise
	 */
	public static boolean fullSync(final Context context,
								   final Account account, final Bundle extras, final String authority,
								   final ContentProviderClient provider, final SyncResult syncResult) {
		NnnLogger.debug(GoogleTaskSync.class, "fullSync");
		if (!PermissionsHelper.hasPermissions(context, PermissionsHelper.PERMISSIONS_GTASKS)) {
			NnnLogger.debug(GoogleTaskSync.class, "Missing permissions, disabling sync");
			SyncGtaskHelper.disableSync(context);
			return false;
		}

		// Is saved at a successful sync
		final long startTime = Calendar.getInstance().getTimeInMillis();

		boolean success = false;
		// Initialize necessary stuff
		final AccountManager accountManager = AccountManager.get(context);

		try {
			GoogleTasksClient client = new GoogleTasksClient(GoogleTasksClient.getAuthToken
					(accountManager, account, NOTIFY_AUTH_FAILURE), Config
					.getGtasksApiKey(context), account.name);

			NnnLogger.debug(GoogleTaskSync.class, "AuthToken acquired, we are connected...");

			// IF full sync, download since start of all time
			// Temporary fix for delete all bug
//					if (PreferenceManager.getDefaultSharedPreferences(context)
//							.getBoolean(SyncPrefs.KEY_FULLSYNC, false)) {
			PreferenceManager.getDefaultSharedPreferences(context)
					.edit()
					.putBoolean(SyncPrefs.KEY_FULLSYNC, false)
					.putLong(PREFS_GTASK_LAST_SYNC_TIME, 0)
					.commit();
//					}

			// Download lists from server
			NnnLogger.debug(GoogleTaskSync.class, "download lists");
			final List<GoogleTaskList> remoteLists = downloadLists(client);

			// merge with local complement
			NnnLogger.debug(GoogleTaskSync.class, "merge lists");
			mergeListsWithLocalDB(context, account.name, remoteLists);

			// Synchronize lists locally
			NnnLogger.debug(GoogleTaskSync.class, "sync lists locally");
			final List<Pair<TaskList, GoogleTaskList>> listPairs = synchronizeListsLocally
					(context, remoteLists);

			// Synchronize lists remotely
			NnnLogger.debug(GoogleTaskSync.class, "sync lists remotely");
			final List<Pair<TaskList, GoogleTaskList>> syncedPairs = synchronizeListsRemotely
					(context, listPairs, client);

			// For each list
			for (Pair<TaskList, GoogleTaskList> syncedPair : syncedPairs) {
				// Download tasks from server
				NnnLogger.debug(GoogleTaskSync.class, "download tasks");
				final List<GoogleTask> remoteTasks = downloadChangedTasks(context, client,
						syncedPair.second);

				// merge with local complement
				NnnLogger.debug(GoogleTaskSync.class, "merge tasks");
				mergeTasksWithLocalDB(context, account.name, remoteTasks, syncedPair.first._id);

				// Synchronize tasks locally
				NnnLogger.debug(GoogleTaskSync.class, "sync tasks locally");
				final List<Pair<Task, GoogleTask>> taskPairs = synchronizeTasksLocally(context,
						remoteTasks, syncedPair);
				// Synchronize tasks remotely
				NnnLogger.debug(GoogleTaskSync.class, "sync tasks remotely");
				synchronizeTasksRemotely(context, taskPairs, syncedPair.second, client);
			}

			NnnLogger.debug(GoogleTaskSync.class, "Sync Complete!");
			success = true;
			PreferenceManager.getDefaultSharedPreferences(context)
					.edit()
					.putLong(PREFS_GTASK_LAST_SYNC_TIME, startTime)
					.commit();
		}
		/*
		TODO re-enable this block once you understand how to handle errors in retrofit2.
		 In the original retrofit (1.9.0), using RetrofitError was enough
		catch (RetrofitError e) {
			NnnLogger.debugOnly(GoogleTaskSync.class, "Retrofit: " + e);
			final int status;
			if (e.getResponse() != null) {
				status = e.getResponse().getStatus();
				NnnLogger.error(GoogleTaskSync.class, "" + status + "; "
						+ e.getResponse().getReason());
			} else {
				status = 999;
			}
			// An HTTP error was encountered.
			switch (status) {
				case 404: // No such item, should never happen, programming error
				case 415: // Not proper body, programming error
				case 400: // Didn't specify url, programming error
					//syncResult.databaseError = true;
				case 401: // Unauthorized, token could possibly just be stale
					// auth-exceptions are hard errors, and if the token is stale,
					// that's too harsh
					//syncResult.stats.numAuthExceptions++;
					// Instead, report ioerror, which is a soft error
				default: // Default is to consider it a networking/server issue
					syncResult.stats.numIoExceptions++;
					break;
			}
		}
		*/ catch (Exception e) {
			// Something went wrong, don't punish the user
			NnnLogger.exception(e);
			syncResult.stats.numIoExceptions++;
		} finally {
			NnnLogger.debug(GoogleTaskSync.class, "SyncResult: " + syncResult.toDebugString());
		}

		return success;
	}

	/**
	 * Loads the remote lists from the database and merges the two lists. If the
	 * remote list contains all lists, then this method only adds local db-ids
	 * to the items. If it does not contain all of them, this loads whatever
	 * extra items are known in the db to the list also.
	 *
	 * Since all lists are expected to be downloaded, any non-existing entries
	 * are assumed to be deleted and marked as such.
	 */
	public static void mergeListsWithLocalDB(final Context context, final String account,
											 final List<GoogleTaskList> remoteLists) {
		NnnLogger.debug(GoogleTaskSync.class,
				"mergeList starting with: " + remoteLists.size());

		final HashMap<String, GoogleTaskList> localVersions = new HashMap<>();
		try (Cursor c = context.getContentResolver().query(
				GoogleTaskList.URI,
				GoogleTaskList.Columns.FIELDS,
				GoogleTaskList.Columns.ACCOUNT + " IS ? AND "
						+ GoogleTaskList.Columns.SERVICE + " IS ?",
				new String[] { account, GoogleTaskList.SERVICENAME }, null)) {
			while (c != null && c.moveToNext()) {
				GoogleTaskList list = new GoogleTaskList(c);
				localVersions.put(list.remoteId, list);
			}
		}

		for (final GoogleTaskList remotelist : remoteLists) {
			// Merge with hashmap
			if (localVersions.containsKey(remotelist.remoteId)) {
				remotelist._id = localVersions.get(remotelist.remoteId)._id;
				remotelist.dbid = localVersions.get(remotelist.remoteId).dbid;
				remotelist.setDeleted(localVersions.get(remotelist.remoteId)
						.isDeleted());
				localVersions.remove(remotelist.remoteId);
			}
		}

		// Remaining ones
		for (final GoogleTaskList list : localVersions.values()) {
			list.remotelyDeleted = true;
			remoteLists.add(list);
		}
		NnnLogger.debug(GoogleTaskSync.class, "mergeList finishing with: " + remoteLists.size());
	}

	/**
	 * Loads the remote tasks from the database and merges the two lists. If the
	 * remote list contains all items, then this method only adds local db-ids
	 * to the items. If it does not contain all of them, this loads whatever
	 * extra items are known in the db to the list also.
	 */
	public static void mergeTasksWithLocalDB(final Context context,
											 final String account, final List<GoogleTask> remoteTasks,
											 long listDbId) {
		final HashMap<String, GoogleTask> localVersions = new HashMap<>();
		try (Cursor c = context.getContentResolver().query(
				GoogleTask.URI,
				GoogleTask.Columns.FIELDS,
				GoogleTask.Columns.LISTDBID + " IS ? AND "
						+ GoogleTask.Columns.ACCOUNT + " IS ? AND "
						+ GoogleTask.Columns.SERVICE + " IS ?",
				new String[] { Long.toString(listDbId), account,
						GoogleTaskList.SERVICENAME }, null)) {
			while (c != null && c.moveToNext()) {
				GoogleTask task = new GoogleTask(c);
				localVersions.put(task.remoteId, task);
			}
		}

		for (final GoogleTask task : remoteTasks) {
			// Set list on remote objects
			task.listdbid = listDbId;
			// Merge with hashmap
			if (localVersions.containsKey(task.remoteId)) {
				task.dbid = localVersions.get(task.remoteId).dbid;
				task.setDeleted(localVersions.get(task.remoteId).isDeleted());
				if (task.isDeleted()) {
					NnnLogger.debug(GoogleTaskSync.class, "merge1: deleting " + task.title);
				}
				localVersions.remove(task.remoteId);
			}
		}

		// Remaining ones
		for (final GoogleTask task : localVersions.values()) {
			remoteTasks.add(task);
			if (task.isDeleted()) {
				NnnLogger.debug(GoogleTaskSync.class, "merge2: was deleted " + task.title);
			}
		}
	}

	/**
	 * Downloads all lists in GTasks and returns them
	 */
	static List<GoogleTaskList> downloadLists(final GoogleTasksClient client) {
		// Do the actual download
		final ArrayList<GoogleTaskList> remoteLists = new ArrayList<>();

		client.listLists(remoteLists);

		// Return list of TaskLists
		return remoteLists;
	}

	/**
	 * Given a list of remote GTaskLists, iterates through it and their versions
	 * (if any) in the local database. If the remote version is newer, the local
	 * version is updated.
	 *
	 * If local list has a remote id, but it does not exist in the list of
	 * remote lists, then it has been deleted remotely and is deleted locally as
	 * well.
	 *
	 * Returns a list of pairs (local, remote).
	 */
	public static List<Pair<TaskList, GoogleTaskList>> synchronizeListsLocally(
			final Context context, final List<GoogleTaskList> remoteLists) {
		final ArrayList<Pair<TaskList, GoogleTaskList>> listPairs = new ArrayList<>();
		// For every list
		for (final GoogleTaskList remoteList : remoteLists) {
			// Compare with local
			NnnLogger.debug(GoogleTaskSync.class, "Loading remote lists from db");
			TaskList localList = loadRemoteListFromDB(context, remoteList);

			if (localList == null) {
				if (remoteList.remotelyDeleted) {
					NnnLogger.debug(GoogleTaskSync.class, "List was remotely deleted1");
					// Deleted locally AND on server
					remoteList.delete(context);
				} else if (remoteList.isDeleted()) {
					NnnLogger.debug(GoogleTaskSync.class, "List was locally deleted");
					// Was deleted locally
				} else {
					// is a new list
					NnnLogger.debug(GoogleTaskSync.class, "Inserting new list: " + remoteList.title);
					localList = new TaskList();
					localList.title = remoteList.title;
					localList.save(context, remoteList.updated);
					// Save id in remote also
					remoteList.dbid = localList._id;
					remoteList.save(context);
				}
			} else {
				// If local is newer, update remote object
				if (remoteList.remotelyDeleted) {
					NnnLogger.debug(GoogleTaskSync.class, "Remote list was deleted2: " + remoteList.title);
					localList.delete(context);
					localList = null;
					remoteList.delete(context);
				} else if (localList.updated > remoteList.updated) {
					NnnLogger.debug(GoogleTaskSync.class, "Local list newer");
					remoteList.title = localList.title;
					// Updated is set by Google
				} else if (localList.updated.equals(remoteList.updated)) {
					// Nothing to do
				} else {
					NnnLogger.debug(GoogleTaskSync.class, "Updating local list: " + remoteList.title);
					// If remote is newer, update local and save to db
					localList.title = remoteList.title;
					localList.save(context, remoteList.updated);
				}
			}
			if (!remoteList.remotelyDeleted)
				listPairs.add(new Pair<>(localList,
						remoteList));
		}

		// Add local lists without a remote version to pairs
		for (final TaskList tl : loadNewListsFromDB(context, remoteLists.get(0))) {
			NnnLogger.debug(GoogleTaskSync.class, "loading new list db: " + tl.title);
			listPairs.add(new Pair<>(tl, null));
		}

		// return pairs
		return listPairs;
	}

	static List<Pair<TaskList, GoogleTaskList>> synchronizeListsRemotely(final Context context,
																		 final List<Pair<TaskList, GoogleTaskList>> listPairs, final GoogleTasksClient client) {

		final List<Pair<TaskList, GoogleTaskList>> syncedPairs = new ArrayList<>();

		// For every list
		for (final Pair<TaskList, GoogleTaskList> pair : listPairs) {
			Pair<TaskList, GoogleTaskList> syncedPair = pair;
			if (pair.second == null) {
				// New list to create
				final GoogleTaskList newList = new GoogleTaskList(pair.first,
						client.accountName);
				client.insertList(newList);
				// Save to db also
				newList.save(context);
				pair.first.save(context, newList.updated);
				syncedPair = new Pair<>(pair.first,
						newList);
			} else if (pair.second.isDeleted()) {
				NnnLogger.debug(GoogleTaskSync.class, "remotesync: isDeletedLocally");
				// Deleted locally, delete remotely also
				pair.second.remotelyDeleted = true;

				// TODO understand how to handle errors in that .deleteList() call, and then
				//  replace the catch{} block with something appropriate
				// try {
				client.deleteList(pair.second);
				/*
				} catch (RetrofitError e) {
					if (e.getResponse() != null && e.getResponse().getStatus() == 400) {
						// Deleted the default list. Ignore error
						NnnLogger.debugOnly(GoogleTaskSync.class,
								"Error when deleting list. This is expected for the default list: " + e);
					} else {
						throw e;
					}
				}
				 */
				// and delete from db if it exists there
				pair.second.delete(context);
				syncedPair = null;
			} else if (pair.first.updated > pair.second.updated) {
				// If local update is different than remote, that means we
				// should update
				client.patchList(pair.second);
				// No need to save remote object
				pair.first.save(context, pair.second.updated);
			}
			// else remote has already been saved locally, nothing to upload
			if (syncedPair != null) {
				syncedPairs.add(syncedPair);
			}
		}
		// return (updated) pairs
		return syncedPairs;
	}

	static void synchronizeTasksRemotely(final Context context,
										 final List<Pair<Task, GoogleTask>> taskPairs,
										 final GoogleTaskList gTaskList,
										 final GoogleTasksClient client) {
		for (final Pair<Task, GoogleTask> pair : taskPairs) {

			// if newly created locally
			if (pair.second == null) {
				NnnLogger.debug(GoogleTaskSync.class, "Second was null");
				final GoogleTask newTask = new GoogleTask(pair.first, client.accountName);
				client.insertTask(newTask, gTaskList);
				newTask.save(context);
				pair.first.save(context, newTask.updated);
			}
			// if deleted locally
			else if (pair.second.isDeleted()) {
				NnnLogger.debug(GoogleTaskSync.class, "Second isDeleted");
				// Delete remote also
				pair.second.remotelydeleted = true;
				client.deleteTask(pair.second, gTaskList);
				// Remove from db
				pair.second.delete(context);
			}
			// if local updated is different from remote,
			// should update remote
			else if (pair.first.updated > pair.second.updated) {
				NnnLogger.debug(GoogleTaskSync.class, "First updated after second");
				client.patchTask(pair.second, gTaskList);
				// No need to save remote object here
				pair.first.save(context, pair.second.updated);
			}
		}
	}

	static TaskList loadRemoteListFromDB(final Context context,
										 final GoogleTaskList remoteList) {
		if (remoteList.dbid == null || remoteList.dbid < 1) return null;

		final Cursor c = context.getContentResolver().query(
				TaskList.getUri(remoteList.dbid), TaskList.Columns.FIELDS,
				null, null, null);
		TaskList tl = null;
		try {
			if (c != null && c.moveToFirst()) {
				tl = new TaskList(c);
			}
		} finally {
			if (c != null) c.close();
		}

		return tl;
	}

	static List<TaskList> loadNewListsFromDB(final Context context,
											 final GoogleTaskList remoteList) {
		final Cursor c = context.getContentResolver().query(TaskList.URI,
				TaskList.Columns.FIELDS,
				GoogleTaskList.getTaskListWithoutRemoteClause(),
				remoteList.getTaskListWithoutRemoteArgs(), null);
		final ArrayList<TaskList> lists = new ArrayList<>();
		try {
			while (c != null && c.moveToNext()) {
				lists.add(new TaskList(c));
			}
		} finally {
			if (c != null) c.close();
		}

		return lists;
	}

	static List<Task> loadNewTasksFromDB(final Context context,
										 final long listdbid, final String account) {
		final Cursor c = context.getContentResolver().query(
				Task.URI,
				Task.Columns.FIELDS,
				GoogleTask.getTaskWithoutRemoteClause(),
				GoogleTask.getTaskWithoutRemoteArgs(listdbid, account,
						GoogleTaskList.SERVICENAME), null);
		final ArrayList<Task> tasks = new ArrayList<>();
		try {
			while (c != null && c.moveToNext()) {
				tasks.add(new Task(c));
			}
		} finally {
			if (c != null) c.close();
		}

		return tasks;
	}

	static List<GoogleTask> downloadChangedTasks(final Context context,
												 final GoogleTasksClient client,
												 final GoogleTaskList remoteList) {
//		final SharedPreferences settings = PreferenceManager
//				.getDefaultSharedPreferences(context);
//		RFC3339Date.asRFC3339(settings.getLong(
//				PREFS_GTASK_LAST_SYNC_TIME, 0))

		return client.listTasks(remoteList);
	}

	static Task loadRemoteTaskFromDB(final Context context,
									 final GoogleTask remoteTask) {
		final Cursor c = context.getContentResolver().query(Task.URI,
				Task.Columns.FIELDS, remoteTask.getTaskWithRemoteClause(),
				remoteTask.getTaskWithRemoteArgs(), null);
		Task t = null;
		try {
			if (c != null && c.moveToFirst()) {
				t = new Task(c);
			}
		} finally {
			if (c != null) c.close();
		}

		return t;
	}

	public static List<Pair<Task, GoogleTask>> synchronizeTasksLocally(
			final Context context, final List<GoogleTask> remoteTasks,
			final Pair<TaskList, GoogleTaskList> listPair) {
		final SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(context);
		final ArrayList<Pair<Task, GoogleTask>> taskPairs = new ArrayList<>();
		// For every list
		for (final GoogleTask remoteTask : remoteTasks) {
			// Compare with local
			Task localTask = loadRemoteTaskFromDB(context, remoteTask);

			// When no local version was found, either
			// a) it was deleted by the user or
			// b) it was created on the server
			if (localTask == null) {
				if (remoteTask.remotelydeleted) {
					NnnLogger.debug(GoogleTaskSync.class, "slocal: task was remotely deleted1: " + remoteTask.title);
					// Nothing to do
					remoteTask.delete(context);
				} else if (remoteTask.isDeleted()) {
					NnnLogger.debug(GoogleTaskSync.class, "slocal: task was locally deleted: " + remoteTask.remoteId);
					// upload change
				} else {
					//NnnLogger.debugOnly(GoogleTaskSync.class, "slocal: task was new: " + remoteTask.title);
					// If no local, and updated is higher than latestupdate,
					// create new
					localTask = new Task();
					localTask.title = remoteTask.title;
					localTask.note = remoteTask.notes;
					localTask.dblist = remoteTask.listdbid;
					// Don't touch time
					if (remoteTask.dueDate != null
							&& !remoteTask.dueDate.isEmpty()) {
						try {
							localTask.due = RFC3339Date.combineDateAndTime(remoteTask.dueDate, localTask.due);
						} catch (Exception ignored) {
						}
					}
					if (remoteTask.status != null
							&& remoteTask.status.equals(GoogleTask.COMPLETED)) {
						localTask.completed = remoteTask.updated;
					}

					localTask.save(context, remoteTask.updated);
					// Save id in remote also
					remoteTask.dbid = localTask._id;
					remoteTask.save(context);
				}
			} else {
				// If local is newer, update remote object
				if (localTask.updated > remoteTask.updated) {
					remoteTask.fillFrom(localTask);
					// Updated is set by Google
				}
				// Remote is newer
				else if (remoteTask.remotelydeleted) {
					NnnLogger.debug(GoogleTaskSync.class, "slocal: task was remotely deleted2: " + remoteTask.title);
					localTask.delete(context);
					localTask = null;
					remoteTask.delete(context);
				} else if (localTask.updated.equals(remoteTask.updated)) {
					// Nothing to do, we are already updated
				} else {
					//NnnLogger.debugOnly(GoogleTaskSync.class, "slocal: task was remotely updated: " + remoteTask.title);
					// If remote is newer, update local and save to db
					localTask.title = remoteTask.title;
					localTask.note = remoteTask.notes;
					localTask.dblist = remoteTask.listdbid;
					if (remoteTask.dueDate != null
							&& !remoteTask.dueDate.isEmpty()) {
						try {
							// dont touch time
							localTask.due = RFC3339Date.combineDateAndTime(remoteTask.dueDate, localTask.due);
						} catch (Exception e) {
							localTask.due = null;
						}
					} else {
						localTask.due = null;
					}

					if (remoteTask.status != null
							&& remoteTask.status.equals(GoogleTask.COMPLETED)) {
						// Only change this if it is not already completed
						if (localTask.completed == null) {
							localTask.completed = remoteTask.updated;
						}
					} else {
						localTask.completed = null;
					}

					localTask.save(context, remoteTask.updated);
				}
			}
			if (remoteTask.remotelydeleted) {
				// Dont
				NnnLogger.debug(GoogleTaskSync.class, "skipping remotely deleted");
			} else if (localTask != null && remoteTask != null
					&& localTask.updated.equals(remoteTask.updated)) {
				NnnLogger.debug(GoogleTaskSync.class, "skipping equal update");
				// Dont
			} else {
				if (localTask != null) {
					NnnLogger.debug(GoogleTaskSync.class, "going to upload: " +
							localTask.title + ", l." + localTask.updated + " r." + remoteTask.updated);
				}
				NnnLogger.debug(GoogleTaskSync.class, "add to sync list: " + remoteTask.title);
				taskPairs.add(new Pair<>(localTask, remoteTask));
			}
		}

		// Add local lists without a remote version to pairs
		for (final Task t : loadNewTasksFromDB(context, listPair.first._id,
				listPair.second.account)) {
			//Log.d("nononsenseapps gtasksync", "adding local only: " + t.title);
			taskPairs.add(new Pair<>(t, null));
		}

		// return pairs
		return taskPairs;
	}
}
