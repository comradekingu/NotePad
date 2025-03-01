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

package com.nononsenseapps.notepad.database;

import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

public class TaskList extends DAO {

	// SQL convention says Table name should be "singular"
	public static final String TABLE_NAME = "tasklist";
	public static final Uri URI = Uri.withAppendedPath(
			Uri.parse(MyContentProvider.SCHEME + MyContentProvider.AUTHORITY),
			TABLE_NAME);

	public static final String VIEWCOUNT_NAME = "lists_with_count";
	public static final Uri URI_WITH_COUNT = Uri.withAppendedPath(URI, VIEWCOUNT_NAME);

	public static Uri getUri(final long id) {
		return Uri.withAppendedPath(URI, Long.toString(id));
	}

	public static final String CONTENT_TYPE = "vnd.android.cursor.item/vnd.nononsenseapps.list";

	public static final int BASEURICODE = 101;
	public static final int BASEITEMCODE = 102;
	public static final int VIEWCOUNTCODE = 103;
	// Legacy support, these also need to use legacy projections
	public static final int LEGACYBASEURICODE = 111;
	public static final int LEGACYBASEITEMCODE = 112;
	public static final int LEGACYVISIBLEURICODE = 113;
	public static final int LEGACYVISIBLEITEMCODE = 114;

	/**
	 * TaskList URIs start at 101, up to 199
	 */
	public static void addMatcherUris(UriMatcher sURIMatcher) {
		sURIMatcher.addURI(MyContentProvider.AUTHORITY, TABLE_NAME, BASEURICODE);
		sURIMatcher.addURI(MyContentProvider.AUTHORITY, TABLE_NAME + "/#", BASEITEMCODE);
		sURIMatcher.addURI(MyContentProvider.AUTHORITY, TABLE_NAME + "/"
				+ VIEWCOUNT_NAME, VIEWCOUNTCODE);

		// Legacy URIs
		sURIMatcher.addURI(MyContentProvider.AUTHORITY,
				LegacyDBHelper.NotePad.Lists.LISTS, LEGACYBASEURICODE);
		sURIMatcher.addURI(MyContentProvider.AUTHORITY,
				LegacyDBHelper.NotePad.Lists.LISTS + "/#", LEGACYBASEITEMCODE);
		sURIMatcher.addURI(MyContentProvider.AUTHORITY,
				LegacyDBHelper.NotePad.Lists.VISIBLE_LISTS,
				LEGACYVISIBLEURICODE);
		sURIMatcher.addURI(MyContentProvider.AUTHORITY,
				LegacyDBHelper.NotePad.Lists.VISIBLE_LISTS + "/#",
				LEGACYVISIBLEITEMCODE);
	}

	public static class Columns implements BaseColumns {

		private Columns() {}

		public static final String TITLE = "title";
		public static final String UPDATED = "updated";
		public static final String LISTTYPE = "tasktype";
		public static final String SORTING = "sorting";

		public static final String VIEW_COUNT = "count";

		public static final String[] FIELDS = { _ID, TITLE, UPDATED, LISTTYPE, SORTING };
		// GTASKACCOUNT, GTASKID };
		public static final String[] SHALLOWFIELDS = { _ID, TITLE, UPDATED };
	}

	public static final String CREATE_TABLE = "CREATE TABLE " +
			TABLE_NAME + "(" + Columns._ID +
			" INTEGER PRIMARY KEY," + Columns.TITLE +
			" TEXT NOT NULL DEFAULT ''," + Columns.UPDATED +
			" INTEGER," + Columns.LISTTYPE +
			" TEXT DEFAULT NULL," + Columns.SORTING +
			" TEXT DEFAULT NULL" + ")";

	public static final String CREATE_COUNT_VIEW = "CREATE TEMP VIEW IF NOT EXISTS " +
			VIEWCOUNT_NAME +
			" AS SELECT " +
			arrayToCommaString(Columns.FIELDS) +
			"," +
			Columns.VIEW_COUNT +
			" FROM " +
			TABLE_NAME +
			" LEFT JOIN " +
			// Select count statement
			" (SELECT COUNT(1) AS " + Columns.VIEW_COUNT +
			"," + Task.Columns.DBLIST + " FROM " +
			Task.TABLE_NAME + " WHERE " +
			Task.Columns.COMPLETED + " IS NULL " +
			" GROUP BY " + Task.Columns.DBLIST + ") " +
			" ON " + TABLE_NAME + "." + Columns._ID +
			" = " + Task.Columns.DBLIST + ";";

	public String title = "";

	// milliseconds since 1970-01-01 UTC
	public Long updated = null;

	// Null, use global prefs
	public String listtype = null;
	public String sorting = null;

	public TaskList() {}

	public TaskList(final Cursor c) {
		this._id = c.getLong(0);
		this.title = c.getString(1);
		this.updated = c.getLong(2);
		this.listtype = c.getString(3);
		this.sorting = c.getString(4);
	}

	public TaskList(final Uri uri, final ContentValues values) {
		this(Long.parseLong(uri.getLastPathSegment()), values);
	}

	public TaskList(final long id, final ContentValues values) {
		this(values);
		this._id = id;
	}

	public TaskList(final JSONObject json) throws JSONException {
		if (json.has(Columns.TITLE))
			title = json.getString(Columns.TITLE);
		if (json.has(Columns.UPDATED))
			updated = json.getLong(Columns.UPDATED);
		if (json.has(Columns.LISTTYPE))
			listtype = json.getString(Columns.LISTTYPE);
		if (json.has(Columns.SORTING))
			sorting = json.getString(Columns.SORTING);
	}

	public TaskList(final ContentValues values) {
		title = values.getAsString(Columns.TITLE);
		updated = values.getAsLong(Columns.UPDATED);
		listtype = values.getAsString(Columns.LISTTYPE);
		sorting = values.getAsString(Columns.SORTING);
	}

	public ContentValues getContent() {
		final ContentValues values = new ContentValues();
		// Note that ID is NOT included here
		values.put(Columns.TITLE, title);
		values.put(Columns.UPDATED, updated);
		values.put(Columns.LISTTYPE, listtype);
		values.put(Columns.SORTING, sorting);
		return values;
	}

	@Override
	protected String getTableName() {
		return TABLE_NAME;
	}

	@Override
	public String getContentType() {
		return CONTENT_TYPE;
	}

	@Override
	public int save(final Context context) {
		return save(context, Calendar.getInstance().getTimeInMillis());
	}

	/**
	 * Insert or update this {@link TaskList} in the database
	 */
	public int save(final Context context, final long updateTime) {
		int result = 0;
		updated = updateTime;
		if (_id < 1) {
			final Uri uri = context
					.getContentResolver()
					.insert(getBaseUri(), getContent());
			if (uri != null) {
				_id = Long.parseLong(uri.getLastPathSegment());
				result++;
			}
		} else {
			result += context
					.getContentResolver()
					.update(getUri(), getContent(), null, null);
		}
		return result;
	}
}
