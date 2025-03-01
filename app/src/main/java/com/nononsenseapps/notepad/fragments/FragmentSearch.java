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

package com.nononsenseapps.notepad.fragments;

import android.app.SearchManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SearchView.OnQueryTextListener;

import androidx.annotation.NonNull;
import androidx.cursoradapter.widget.SimpleCursorAdapter;
import androidx.cursoradapter.widget.SimpleCursorAdapter.ViewBinder;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.preference.PreferenceManager;

import com.nononsenseapps.notepad.R;
import com.nononsenseapps.notepad.database.Task;
import com.nononsenseapps.ui.TitleNoteTextView;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EFragment;
import org.androidannotations.annotations.SystemService;
import org.androidannotations.annotations.ViewById;

/**
 * This is used only in the "Archive" view, for deleted notes
 */
@EFragment(resName = "fragment_search")
public class FragmentSearch extends Fragment {

	public final static String QUERY = "query";

	@SystemService
	protected
	SearchManager searchManager;

	@ViewById(resName = "list")
	protected
	ListView list;

	protected SimpleCursorAdapter mAdapter;

	protected LoaderCallbacks<Cursor> mCallback;

	protected String mQuery = "";

	protected SearchView mSearchView;

	public static FragmentSearch_ getInstance(final String initialQuery) {
		FragmentSearch_ f = new FragmentSearch_();
		Bundle args = new Bundle();
		args.putString(QUERY, initialQuery);
		f.setArguments(args);
		return f;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (getArguments() != null) {
			if (getArguments().containsKey(QUERY))
				mQuery = getArguments().getString(QUERY);
		}

		setHasOptionsMenu(true);
	}

	@Override
	public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);

		// allow the user to search among the previously deleted notes
		inflater.inflate(R.menu.fragment_search, menu);

		// Get the SearchView and set the searchable configuration
		mSearchView = (SearchView) menu.findItem(R.id.menu_search)
				.getActionView();
		// Assumes current activity is the searchable activity
		mSearchView.setSearchableInfo(searchManager
				.getSearchableInfo(getActivity().getComponentName()));
		mSearchView.setIconifiedByDefault(false); // Do not iconify the widget;
		// expand it by default
		mSearchView.setQueryRefinementEnabled(true);
		mSearchView.setSubmitButtonEnabled(false);

		// Disable suggestions in search activity
		mSearchView.setSuggestionsAdapter(null);

		mSearchView.setOnQueryTextListener(new OnQueryTextListener() {
			@Override
			public boolean onQueryTextSubmit(final String query) {
				doSearch(query);
				return true;
			}

			@Override
			public boolean onQueryTextChange(final String query) {
				doSearch(query);
				return true;
			}
		});

		mSearchView.setQuery(mQuery, false);
	}

	@AfterViews
	void setupAdapter() {
		mAdapter = getAdapter();

		mAdapter.setViewBinder(getViewBinder());

		// Set adapter
		list.setAdapter(mAdapter);
		list.setOnItemClickListener(getOnItemClickListener());

		// Start loading data
		mCallback = new LoaderCallbacks<>() {
			@NonNull
			@Override
			public Loader<Cursor> onCreateLoader(int id, Bundle arg1) {
				return new CursorLoader(getActivity(), getSearchUri(), getFields(),
						null, new String[] { mQuery }, getSortOrder());
			}

			@Override
			public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor c) {
				mAdapter.swapCursor(c);
			}

			@Override
			public void onLoaderReset(@NonNull Loader<Cursor> loader) {
				mAdapter.swapCursor(null);
			}
		};

		doSearch(mQuery);
	}

	protected void doSearch(final String query) {
		mQuery = query == null ? "" : query;

		// If not loaded yet, let it load
		if (mCallback != null)
			LoaderManager.getInstance(this).restartLoader(0, null, mCallback);
	}

	/**
	 * Override to give different search behaviour
	 */
	protected Uri getSearchUri() {
		return Task.URI_SEARCH;
	}

	/**
	 * Override to give different search behaviour
	 */
	protected String[] getFields() {
		return Task.Columns.FIELDS;
	}

	/**
	 * Override to give different search behaviour
	 */
	protected String getSortOrder() {
		return Task.Columns.TITLE;
	}

	/**
	 * Override to get different search behaviour
	 */
	protected SimpleCursorAdapter getAdapter() {
		return new SimpleCursorAdapter(
				getActivity(),
				R.layout.tasklist_item_rich,
				null,
				new String[] { Task.Columns.TITLE, Task.Columns.NOTE, Task.Columns.DUE,
						Task.Columns.COMPLETED, Task.Columns.LEFT, Task.Columns.RIGHT },
				new int[] { android.R.id.text1, android.R.id.text1, R.id.date, R.id.checkbox,
						R.id.drag_handle, R.id.dragpadding },
				0);
	}

	/**
	 * Override to give different search behaviour
	 */
	protected OnItemClickListener getOnItemClickListener() {
		return (arg0, origin, pos, id)
				-> startActivity(new Intent(Intent.ACTION_EDIT, Task.getUri(id)));
	}

	/**
	 * Override to give different search behaviour
	 */
	protected ViewBinder getViewBinder() {
		// Get the global list settings
		final SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(getActivity());

		// Load pref for item height, or show 3 lines if it was not set
		final int rowCount = prefs.getInt(getString(R.string.key_pref_item_max_height), 3);

		return (view, c, colIndex) -> {
			switch (colIndex) {
				// Matches order in Task.Columns.Fields
				case 1:
					// Title
					String sTemp = c.getString(colIndex);

					// Set height of text for non-headers
					if (rowCount == 1) {
						((TitleNoteTextView) view).setSingleLine(true);
					} else {
						((TitleNoteTextView) view).setSingleLine(false);
						((TitleNoteTextView) view).setMaxLines(rowCount);
					}

					// Change color based on complete status
					((TitleNoteTextView) view).useSecondaryColor(!c.isNull(3));

					((TitleNoteTextView) view).setTextTitle(sTemp);
					return true;
				case 2:
					// Note
					// Only if task it not locked
					if (c.getInt(9) != 1) {
						((TitleNoteTextView) view).setTextRest(c
								.getString(colIndex));
					} else {
						((TitleNoteTextView) view).setTextRest("");
					}
					return true;
				default:
					// Checkbox
					view.setVisibility(View.GONE);
					return true;
			}
		};
	}
}
