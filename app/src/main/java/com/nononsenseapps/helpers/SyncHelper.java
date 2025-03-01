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

package com.nononsenseapps.helpers;

import android.content.Context;

import com.nononsenseapps.notepad.sync.orgsync.OrgSyncService;

/**
 * This class handles sync logic. No other class should request a sync.
 */
public class SyncHelper {

	// TODO class may be useless. check who calls onManualSyncRequest()

	public static boolean onManualSyncRequest(final Context context) {
		boolean syncing = false;

		// GTasks
		if (SyncGtaskHelper.isGTasksConfigured(context)) {
			syncing = true;
			SyncGtaskHelper.requestSyncIf(context, SyncGtaskHelper.MANUAL);
		}

		// Others
		if (OrgSyncService.areAnyEnabled(context)) {
			syncing = true;
			OrgSyncService.start(context);
		}

		return syncing;
	}
}