/*
 * Copyright (C) 2012 Jonas Kalderstam
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.nononsenseapps.notepad.prefs;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.widget.Toast;

import com.nononsenseapps.helpers.Log;
import com.nononsenseapps.notepad.R;
import com.nononsenseapps.notepad.fragments.DialogExportBackup;
import com.nononsenseapps.notepad.fragments.DialogRestoreBackup;
import com.nononsenseapps.notepad.sync.files.JSONBackup;

import java.io.FileNotFoundException;

public class BackupPrefs extends PreferenceFragment {

	public static final String KEY_IMPORT = "backup_import";
	public static final String KEY_EXPORT = "backup_export";

	private JSONBackup backupMaker;
	private RestoreBackupTask bgTask;

	/**
	 * Run the backup in the background. Locking the UI-thread for up to a few
	 * seconds is not nice...
	 */
	private class RestoreBackupTask extends AsyncTask<Void, Void, Integer> {
		protected Integer doInBackground(Void... params) {
			try {
				backupMaker.restoreBackup();
				return 0;
			} catch (FileNotFoundException e) {
				return 1;
			} catch (Exception e) {
				return 2;
			}
		}

		protected void onPostExecute(final Integer result) {
			Context context = getActivity();
			if (context == null) {
				return;
			}
			switch (result) {
				case 0:
					Toast.makeText(getActivity(), R.string.backup_import_success,
							Toast.LENGTH_SHORT).show();
					break;
				case 1:
					Toast.makeText(getActivity(), R.string.backup_file_not_found,
							Toast.LENGTH_SHORT).show();
					break;
				case 2:
					Toast.makeText(getActivity(), R.string.backup_import_failed,
							Toast.LENGTH_SHORT).show();
					break;
			}
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Load the preferences from an XML resource
		addPreferencesFromResource(R.xml.app_pref_backup);

		backupMaker = new JSONBackup(getActivity());

		findPreference(KEY_IMPORT).setOnPreferenceClickListener(
				preference -> {
					DialogRestoreBackup.showDialog(getFragmentManager(),
							() -> {
								bgTask = new RestoreBackupTask();
								bgTask.execute();
							});
					return true;
				});

		findPreference(KEY_EXPORT).setOnPreferenceClickListener(
				preference -> {
					DialogExportBackup.showDialog(getFragmentManager(), () -> {
						try {
							backupMaker.writeBackup();
							Toast.makeText(getActivity(), R.string.backup_export_success, Toast.LENGTH_SHORT)
									.show();
						} catch (Exception e) {
							Log.exception(e);
							Toast.makeText(getActivity(), R.string.backup_export_failed, Toast.LENGTH_SHORT)
									.show();
						}
					});

					return true;
				});
	}
}