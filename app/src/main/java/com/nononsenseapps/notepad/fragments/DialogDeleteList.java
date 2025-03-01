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

import android.os.Bundle;
import android.widget.Toast;

import androidx.fragment.app.FragmentManager;

import com.nononsenseapps.notepad.R;
import com.nononsenseapps.notepad.database.TaskList;

public class DialogDeleteList extends DialogConfirmBase {
	static final String ID = "id";
	static final String TAG = "deletelistok";

	public static void showDialog(final FragmentManager fm, final long listId,
								  final DialogConfirmedListener listener) {
		DialogDeleteList d = new DialogDeleteList();
		d.setListener(listener);
		Bundle args = new Bundle();
		args.putLong(ID, listId);
		d.setArguments(args);
		d.show(fm, TAG);
	}

	@Override
	public int getTitle() {
		return R.string.delete_question;
	}

	@Override
	public int getMessage() {
		return R.string.delete_list_message;
	}

	@Override
	public void onOKClick() {
		if (getArguments().getLong(ID, -1) > 0) {
			if (0 < getActivity().getContentResolver()
					.delete(TaskList.getUri(getArguments().getLong(ID, -1)),
							null, null)) {
				Toast.makeText(getActivity(), R.string.deleted,
						Toast.LENGTH_SHORT).show();
			}
		}
		if (listener != null) {
			listener.onConfirm();
		}
		getDialog().dismiss();
	}

}
