package com.nononsenseapps.notepad;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;

import com.nononsenseapps.helpers.ActivityHelper;
import com.nononsenseapps.helpers.TimeFormatter;
import com.nononsenseapps.notepad.database.Task;
import com.nononsenseapps.ui.TitleNoteTextView;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.SeekBarProgressChange;
import org.androidannotations.annotations.ViewById;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

@EActivity(resName = "activity_task_history")
public class ActivityTaskHistory extends AppCompatActivity {
	public static final String RESULT_TEXT_KEY = "task_text_key";
	private long mTaskID;
	private boolean loaded = false;
	private Cursor mCursor;

	@ViewById(resName = "seekBar")
	SeekBar seekBar;

	@ViewById(resName = "taskText")
	TitleNoteTextView taskText;

	@ViewById(resName = "timestamp")
	TextView timestamp;

	private SimpleDateFormat timeFormatter;
	private SimpleDateFormat dbTimeParser;

	@SuppressLint("SimpleDateFormat")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		// Must do this before super.onCreate
		ActivityHelper.readAndSetSettings(this);
		super.onCreate(savedInstanceState);

		// Intent must contain a task id
		if (getIntent() == null || getIntent().getLongExtra(Task.Columns._ID, -1) < 1) {
			setResult(RESULT_CANCELED, new Intent());
			finish();
			return;
		} else {
			mTaskID = getIntent().getLongExtra(Task.Columns._ID, -1);
		}

		timeFormatter = TimeFormatter.getLocalFormatterLong(this);
		// Default datetime format in sqlite. Set to UTC timezone
		dbTimeParser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		dbTimeParser.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	@AfterViews
	void loadLayout() {
		// Prepare for failure
		setResult(RESULT_CANCELED, new Intent());
		// Inflate a "Done/Discard" custom action bar view.
		LayoutInflater inflater = (LayoutInflater) getSupportActionBar()
				.getThemedContext()
				.getSystemService(LAYOUT_INFLATER_SERVICE);
		@SuppressLint("InflateParams") final View customActionBarView = inflater
				.inflate(R.layout.actionbar_custom_view_done_discard, null);
		customActionBarView
				.findViewById(R.id.actionbar_done)
				.setOnClickListener(v -> {
					// "Done"
					final Intent returnIntent = new Intent();
					returnIntent.putExtra(RESULT_TEXT_KEY, taskText.getText().toString());
					setResult(RESULT_OK, returnIntent);
					finish();
				});
		customActionBarView
				.findViewById(R.id.actionbar_discard)
				.setOnClickListener(v -> {
					// "Cancel result already set"
					finish();
				});

		// Show the custom action bar view and hide the normal Home icon and title.
		getSupportActionBar()
				.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
						ActionBar.DISPLAY_SHOW_CUSTOM | ActionBar.DISPLAY_SHOW_HOME
								| ActionBar.DISPLAY_SHOW_TITLE);
		getSupportActionBar()
				.setCustomView(customActionBarView, new ActionBar.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
	}

	@Override
	public void onStart() {
		super.onStart();
		LoaderManager.getInstance(this).restartLoader(0, null,
				new LoaderCallbacks<Cursor>() {

					@NonNull
					@Override
					public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
						return new CursorLoader(ActivityTaskHistory.this,
								Task.URI_TASK_HISTORY, Task.Columns.HISTORY_COLUMNS_UPDATED,
								Task.Columns.HIST_TASK_ID + " IS ?",
								new String[] { Long.toString(mTaskID) }, null);
					}

					@Override
					public void onLoadFinished(@NonNull Loader<Cursor> arg0, Cursor c) {
						mCursor = c;
						setSeekBarProperties();
						if (!loaded) {
							seekBar.setProgress(c.getCount() - 1);
							loaded = true;
						}
					}

					@Override
					public void onLoaderReset(@NonNull Loader<Cursor> arg0) {
						mCursor = null;
						setSeekBarProperties();
					}
				});
	}

	@SeekBarProgressChange(resName = "seekBar")
	void onSeekBarChanged(int progress) {
		if (mCursor == null) return;

		if (progress < mCursor.getCount()) {
			mCursor.moveToPosition(progress);
			taskText.setTextTitle(mCursor.getString(1));
			taskText.setTextRest(mCursor.getString(2));
			try {
				Date x = dbTimeParser.parse(mCursor.getString(3));
				timestamp.setText(timeFormatter.format(x));
			} catch (ParseException e) {
				Log.d("nononsenseapps time", e.getLocalizedMessage());
			}
		}
	}

	void setSeekBarProperties() {
		if (mCursor == null) {
			seekBar.setEnabled(false);
			seekBar.setMax(0);
		} else {
			seekBar.setEnabled(true);
			seekBar.setMax(mCursor.getCount() - 1);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		return false;
	}
}
