/*
 * Copyright (c) 2014 Jonas Kalderstam.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nononsenseapps.notepad.test;

import static org.junit.Assert.*;

import android.Manifest;

import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.nononsenseapps.notepad.dashclock.TasksSettings;
import com.squareup.spoon.Spoon;

import org.junit.*;

/**
 * Verify that the activity opens OK on any screensize.
 */

public class DashClockSettingsTest {

	@Rule
	public ActivityTestRule<TasksSettings> mActivityRule
			= new ActivityTestRule<>(TasksSettings.class,false);

	@Rule
	public GrantPermissionRule permissionToSaveScreenshots = GrantPermissionRule.grant(
			Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE);

	@Test
	public void testLoadOK() {
		assertNotNull(mActivityRule.getActivity());
		Spoon.screenshot(mActivityRule.getActivity(), "Activity_loaded");
	}
}
