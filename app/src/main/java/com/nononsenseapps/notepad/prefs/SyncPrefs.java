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

package com.nononsenseapps.notepad.prefs;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import com.nononsenseapps.build.Config;
import com.nononsenseapps.helpers.NnnLogger;
import com.nononsenseapps.notepad.R;
import com.nononsenseapps.notepad.database.MyContentProvider;
import com.nononsenseapps.notepad.sync.googleapi.GoogleTasksClient;
import com.nononsenseapps.notepad.sync.orgsync.OrgSyncService;
import com.nononsenseapps.helpers.FileHelper;
import com.nononsenseapps.helpers.PermissionsHelper;
import com.nononsenseapps.helpers.PreferencesHelper;
import com.nononsenseapps.helpers.SyncGtaskHelper;

import java.io.IOException;

public class SyncPrefs extends PreferenceFragmentCompat
		implements OnSharedPreferenceChangeListener {

	// TODO these 6 are useles. Maybe we can reuse them if we find a newer sync service
	//  to replace google tasks
	public static final String KEY_SYNC_ENABLE = "syncEnablePref";
	public static final String KEY_ACCOUNT = "accountPref";
	public static final String KEY_FULLSYNC = "syncFull";
	public static final String KEY_SYNC_ON_START = "syncOnStart";
	public static final String KEY_SYNC_ON_CHANGE = "syncOnChange";
	public static final String KEY_BACKGROUND_SYNC = "syncInBackground";

	/**
	 * Used for sync on start and on change
	 */
	public static final String KEY_LAST_SYNC = "lastSync";
	private static final int PICK_ACCOUNT_CODE = 2;

	// SD sync
	public static final String KEY_SD_ENABLE = "pref_sync_sd_enabled";
	public static final String KEY_SD_DIR_URI = "pref_sync_sd_dir_uri";
	public static final String KEY_SD_DIR = "pref_sync_sd_dir";
	private static final int PICK_SD_DIR_CODE = 1;

	private Activity activity;

	private SwitchPreference prefSyncEnable;
	private Preference prefAccount;

	/**
	 * Where you click to choose the directory to save the org files
	 */
	private Preference prefSdDirURI;

	public static void setSyncInterval(Context activity, SharedPreferences sharedPreferences) {
		String accountName = sharedPreferences.getString(KEY_ACCOUNT, "");
		boolean backgroundSync = sharedPreferences.getBoolean(KEY_BACKGROUND_SYNC, false);

		if (accountName != null && !accountName.isEmpty()) {
			Account account = SyncGtaskHelper
					.getAccount(AccountManager.get(activity), accountName);
			if (account != null) {
				if (!backgroundSync) {
					// Disable periodic syncing
					ContentResolver.removePeriodicSync(account,
							MyContentProvider.AUTHORITY, new Bundle());
				} else {
					// Convert from minutes to seconds
					long pollFrequency = 3600;
					// Set periodic syncing
					ContentResolver.addPeriodicSync(account, MyContentProvider.AUTHORITY,
							new Bundle(), pollFrequency);
				}
			}
		}
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		this.activity = activity;
	}

	@Override
	public void onCreatePreferences(@Nullable Bundle savInstState, String rootKey) {

		// Load the preferences from an XML resource
		addPreferencesFromResource(R.xml.app_pref_sync);

		final SharedPreferences sharedPrefs = PreferenceManager
				.getDefaultSharedPreferences(activity);
		// Set up a listener whenever a key changes
		sharedPrefs.registerOnSharedPreferenceChangeListener(this);

		// TODO this is useless, since all Google Tasks settings are disabled.
		prefAccount = findPreference(KEY_ACCOUNT);
		setAccountTitle(sharedPrefs);
		prefAccount.setOnPreferenceClickListener(preference -> {
			// ask for permissions needed to use google tasks
			// TODO useless, remove
			boolean granted = PermissionsHelper
					.hasPermissions(this.getContext(), PermissionsHelper.PERMISSIONS_GTASKS);
			if (granted) {
				// Show dialog
				showAccountDialog();
			} else {
				this.requestPermissions(
						PermissionsHelper.PERMISSIONS_GTASKS,
						PermissionsHelper.REQUEST_CODE_GTASKS_PERMISSIONS);
			}
			return true;
		});

		prefSyncEnable = (SwitchPreference) findPreference(KEY_SYNC_ENABLE);
		// Disable prefs if this is not correct build
		String API_KEY = Config.getGtasksApiKey(getActivity());
		prefSyncEnable.setEnabled(null != API_KEY && !API_KEY.contains(" "));

		findPreference(KEY_SD_ENABLE).setOnPreferenceClickListener(p -> {
			boolean ok = PermissionsHelper.hasPermissions(
					this.getContext(), PermissionsHelper.PERMISSIONS_SD);
			if (!ok) {
				this.requestPermissions(PermissionsHelper.PERMISSIONS_SD,
						PermissionsHelper.REQUEST_CODE_SD_PERMISSIONS);
				// continues in onRequestPermissionsResult()
			}
			return true;
		});

		// folder URI for SD sync on the internal storage.
		// this setting is DISABLED because the code can't use the URIs provided by the filepicker
		prefSdDirURI = findPreference(KEY_SD_DIR_URI);
		setSummaryForSdDirURI(sharedPrefs);

		// when the user clicks on the settings entry to choose the directory, do this
		prefSdDirURI.setOnPreferenceClickListener(preference -> {
			boolean ok = PermissionsHelper.hasPermissions(
					this.getContext(), PermissionsHelper.PERMISSIONS_SD);
			if (ok) {
				// we CAN read the filesystem => show the filepicker
				showFolderPickerActivity();
			} else {
				this.requestPermissions(
						PermissionsHelper.PERMISSIONS_SD,
						PermissionsHelper.REQUEST_CODE_SD_PERMISSIONS);
				// continues in onRequestPermissionsResult()
			}
			// tell android to update the preference value
			return true;
		});

		// for the preference that shows a popup to choose among a few possible folders
		BackupPrefs.setupFolderListPreference(this.getContext(), this, KEY_SD_DIR);
	}

	@Override
	public void onRequestPermissionsResult(int reqCode, @NonNull String[] permissions,
										   @NonNull int[] grantResults) {
		// if we got all permissions
		boolean granted = PermissionsHelper.permissionsGranted(permissions, grantResults);

		switch (reqCode) {
			case PermissionsHelper.REQUEST_CODE_SD_PERMISSIONS:
				if (!granted) {
					// warn the user that the permission was denied
					Toast.makeText(this.getContext(), R.string.permission_denied,
							Toast.LENGTH_SHORT).show();
					PreferencesHelper.disableSdCardSync(this.getContext());

					// SD card synchronization was disabled, but the UI does not know: reload
					var myPref = (SwitchPreference) findPreference(KEY_SD_ENABLE);
					if (myPref.isChecked()) myPref.setChecked(false);
				}
				break;
			case PermissionsHelper.REQUEST_CODE_GTASKS_PERMISSIONS:
				if (granted) {
					// Success => open the dialog
					showAccountDialog();
				} else {
					// user refused: show warning and disable sync
					Toast.makeText(this.getContext(), R.string.permission_denied,
							Toast.LENGTH_SHORT).show();
					PreferencesHelper
							.put(getActivity(), SyncPrefs.KEY_SYNC_ENABLE, false);
				}
				break;
			default:
				break;
		}

		super.onRequestPermissionsResult(reqCode, permissions, grantResults);
	}

	/**
	 * Shows the system's default filepicker, to  let the user choose a directory. See:
	 * https://developer.android.com/training/data-storage/shared/documents-files#grant-access-directory
	 */
	private void showFolderPickerActivity() {
		var i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

		// don't add this: it stops working on some devices, like the emulator with API 25!
		// i.setType(DocumentsContract.Document.MIME_TYPE_DIR);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

			// specify a URI for the directory that should be opened in
			// the system file picker when it loads.
			var sharPrefs = PreferenceManager.getDefaultSharedPreferences(activity);

			// get the previously selected Uri, if available
			String oldSetting = sharPrefs.getString(KEY_SD_DIR_URI, null);
			if (oldSetting != null) {
				Uri uriToLoad = Uri.parse(oldSetting);
				i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uriToLoad);
			}
			// else the filepicker will just open in its default state. whatever.
		}

		try {
			// Start the built-in filepicker
			startActivityForResult(i, PICK_SD_DIR_CODE);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this.getContext(),
					R.string.file_picker_not_available, Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		PreferenceManager.getDefaultSharedPreferences(activity)
				.unregisterOnSharedPreferenceChangeListener(this);
	}

	/**
	 * Shows a system popup to choose the google account to use for synchronizing notes.
	 * If the user has no google accounts on the device, a prompt will open, asking to
	 * add a new one
	 */
	private void showAccountDialog() {
		// do we need to check for permissions ? (there's 3 in the manifest)
		String hint = this.getString(R.string.select_account);
		var allowedAccountTypes = new String[] { "com.google" };
		Intent i = AccountManager.newChooseAccountIntent(null, null,
				allowedAccountTypes, hint, null, null,
				null);
		startActivityForResult(i, PICK_ACCOUNT_CODE);
	}

	/**
	 * Called when a shared preference is changed, added, or removed. This
	 * may be called even if a preference is set to its existing value.
	 * <p/>
	 * <p>This callback will be run on your main thread.
	 *
	 * @param prefs The {@link SharedPreferences} that received the change.
	 * @param key   The key of the preference that was changed, added, or removed
	 */
	public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
		try {
			NnnLogger.debug(SyncPrefs.class, "onChanged");
			if (activity.isFinishing()) {
				// Setting the summary now would crash it with
				// IllegalStateException since we are not attached to a view
				return;
			}

			// => now we can safely continue
			switch (key) {
				case KEY_SYNC_ENABLE:
					toggleSync(prefs);
					break;
				case KEY_BACKGROUND_SYNC:
					setSyncInterval(activity, prefs);
					break;
				case KEY_ACCOUNT:
					NnnLogger.debug(SyncPrefs.class, "account");
					prefAccount.setTitle(prefs.getString(KEY_ACCOUNT, ""));
					// prefAccount.setSummary(getString(R.string.settings_account_summary));
					break;
				case KEY_SD_ENABLE:
				case KEY_SD_DIR:
					// Restart the sync service
					OrgSyncService.stop(getActivity());
					break;
				case KEY_SD_DIR_URI:
					setSummaryForSdDirURI(prefs);
					break;
			}
		} catch (IllegalStateException e) {
			// This is just in case the "isFinishing" wouldn't be enough
			// The isFinishing will try to prevent us from doing something stupid
			// This catch prevents the app from crashing if we do something stupid
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (resultCode != Activity.RESULT_OK) {
			// it was cancelled by the user. Let's ignore it in both cases
			return;
		}

		if (requestCode == PICK_ACCOUNT_CODE) {
			// the user has confirmed with a valid account
			String chosenAccountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
			userChoseAnAccountWithName(chosenAccountName);
			return;
		}

		if (requestCode != PICK_SD_DIR_CODE) {
			// if it wasn't the filepiker nor the account picker, exit now
			super.onActivityResult(requestCode, resultCode, data);
			return;
		}

		// "data" contains the URI for the user-selected directory, A.K.A. the "document tree"
		onSdDirUriPicked(data.getData());
	}

	/**
	 * Called when the user picks a "directory" with the system's filepicker
	 *
	 * @param uri points to the chosen "directory"
	 */
	private void onSdDirUriPicked(Uri uri) {

		// represents the directory that the user just picked
		// Use this instead of the "File" class
		var docDir = DocumentFile.fromTreeUri(this.getContext(), uri);

		if (FileHelper.documentIsWritableFolder(docDir)) {
			// save it
			PreferenceManager
					.getDefaultSharedPreferences(getActivity())
					.edit()
					.putString(KEY_SD_DIR_URI, uri.toString())
					.apply();
			Toast.makeText(getActivity(), R.string.feature_is_WIP, Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(getActivity(), R.string.cannot_write_to_directory, Toast.LENGTH_SHORT)
					.show();
		}
	}

	/**
	 * Called when the user chooses one {@link Account} from the system popup
	 */
	private void userChoseAnAccountWithName(String chosenAccountName) {
		Account[] allAccounts = AccountManager.get(this.activity).getAccountsByType("com.google");

		for (var chosenAccount : allAccounts) {
			if (!chosenAccount.name.equalsIgnoreCase(chosenAccountName)) continue;

			// we got the 1° (and hopefully only) match: proceed
			NnnLogger.debug(SyncPrefs.class, "step one");

			// work continues in callback, method afterGettingAuthToken()
			AccountManagerCallback<Bundle> callback = (b) -> afterGettingAuthToken(b, chosenAccount);

			// Request user's permission
			GoogleTasksClient.getAuthTokenAsync(activity, chosenAccount, callback);

			// do that only for the 1° match
			return;
		}
	}

	/**
	 * Called when the user has selected a Google account when pressing the enable Gtask
	 * switch. User wants to select an account to sync with. If we get an approval,
	 * activate sync and set periodicity also.
	 */
	private void afterGettingAuthToken(AccountManagerFuture<Bundle> future, Account account) {
		try {
			NnnLogger.debug(SyncPrefs.class, "step two");
			// If the user has authorized your application to use the tasks API a token is available.
			// TODO here it crashes because the app is not registered into some kind of console
			String token = future.getResult().getString(AccountManager.KEY_AUTHTOKEN);

			// Now we are authorized by the user.
			NnnLogger.debug(SyncPrefs.class, "step two-b: " + token);

			if (token != null && !token.isEmpty() && account != null) {

				// Also mark enabled as true, as the dialog was shown from enable button
				NnnLogger.debug(SyncPrefs.class, "step three: " + account.name);

				SharedPreferences customSharedPreference = PreferenceManager
						.getDefaultSharedPreferences(activity);
				customSharedPreference
						.edit()
						.putString(SyncPrefs.KEY_ACCOUNT, account.name)
						.putBoolean(KEY_SYNC_ENABLE, true)
						.commit();

				// Set it syncable
				ContentResolver.setSyncAutomatically(account, MyContentProvider.AUTHORITY, true);
				ContentResolver.setIsSyncable(account, MyContentProvider.AUTHORITY, 1);
				// Set sync frequency
				SyncPrefs.setSyncInterval(activity, customSharedPreference);
				// Set it syncable
				SyncGtaskHelper.toggleSync(this.activity, customSharedPreference);
				// And schedule an immediate sync
				SyncGtaskHelper.requestSyncIf(this.activity, SyncGtaskHelper.MANUAL);
			}
		} catch (OperationCanceledException | AuthenticatorException | IOException e) {
			// OperationCanceledException:
			// * if the request was canceled for any reason
			// AuthenticatorException:
			// * if there was an error communicating with the authenticator or
			// * if the authenticator returned an invalid response or
			// * if the user did not register on the api console
			// IOException:
			// * if the authenticator returned an error response that
			// * indicates that it encountered an IOException while
			// * communicating with the authentication server
			String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
			Toast.makeText(this.activity, errMsg, Toast.LENGTH_SHORT).show();
			SyncGtaskHelper.disableSync(this.activity);
		}
	}


	private void toggleSync(SharedPreferences sharedPreferences) {
		boolean enabled = SyncGtaskHelper.toggleSync(getActivity(), sharedPreferences);
		if (enabled) {
			showAccountDialog();
		} else {
			// Synchronize view also
			if (prefSyncEnable.isChecked()) prefSyncEnable.setChecked(false);
		}
	}

	private void setAccountTitle(final SharedPreferences sharedPreferences) {
		prefAccount.setTitle(sharedPreferences.getString(KEY_ACCOUNT, ""));
		prefAccount.setSummary(R.string.settings_account_summary);
	}

	/**
	 * Writes the description in the preferences item that lets users open the filepicker.
	 * It is currently disabled
	 */
	private void setSummaryForSdDirURI(final SharedPreferences sharedPreferences) {
		String actualDir = FileHelper.getUserSelectedOrgDir(getContext());
		String valToSet = sharedPreferences.getString(KEY_SD_DIR_URI, null);
		if (valToSet == null) {
			// The filepicker was never used => show the actual directory in the description
			valToSet = actualDir;
		} else {
			// show the file path that the uri from the filepicker is pointing to,
			// and the one that will be actually used
			valToSet = getContext().getString(R.string.filepicker_preference_description,
					Uri.parse(valToSet).getPath(), actualDir);
		}
		prefSdDirURI.setSummary(valToSet);
	}

}