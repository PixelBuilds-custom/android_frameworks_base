/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.STATE_ON;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.ContentRecordingSession;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.wm.ContentRecorder.MediaProjectionManagerWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


/**
 * Tests for the {@link ContentRecorder} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ContentRecorderTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ContentRecorderTests extends WindowTestsBase {
    private static IBinder sTaskWindowContainerToken;
    private DisplayContent mVirtualDisplayContent;
    private Task mTask;
    private final ContentRecordingSession mDisplaySession =
            ContentRecordingSession.createDisplaySession(DEFAULT_DISPLAY);
    private final ContentRecordingSession mWaitingDisplaySession =
            ContentRecordingSession.createDisplaySession(DEFAULT_DISPLAY);
    private ContentRecordingSession mTaskSession;
    private static Point sSurfaceSize;
    private ContentRecorder mContentRecorder;
    @Mock private MediaProjectionManagerWrapper mMediaProjectionManagerWrapper;
    private SurfaceControl mRecordedSurface;

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);

        // GIVEN SurfaceControl can successfully mirror the provided surface.
        sSurfaceSize = new Point(
                mDefaultDisplay.getDefaultTaskDisplayArea().getBounds().width(),
                mDefaultDisplay.getDefaultTaskDisplayArea().getBounds().height());
        mRecordedSurface = surfaceControlMirrors(sSurfaceSize);

        doReturn(INVALID_DISPLAY).when(mWm.mDisplayManagerInternal).getDisplayIdToMirror(anyInt());

        // GIVEN the VirtualDisplay associated with the session (so the display has state ON).
        DisplayInfo displayInfo = mDisplayInfo;
        displayInfo.logicalWidth = sSurfaceSize.x;
        displayInfo.logicalHeight = sSurfaceSize.y;
        displayInfo.state = STATE_ON;
        mVirtualDisplayContent = createNewDisplay(displayInfo);
        final int displayId = mVirtualDisplayContent.getDisplayId();
        mContentRecorder = new ContentRecorder(mVirtualDisplayContent,
                mMediaProjectionManagerWrapper);
        spyOn(mVirtualDisplayContent);

        // GIVEN MediaProjection has already initialized the WindowToken of the DisplayArea to
        // record.
        mDisplaySession.setVirtualDisplayId(displayId);
        mDisplaySession.setDisplayToRecord(mDefaultDisplay.mDisplayId);

        // GIVEN there is a window token associated with a task to record.
        sTaskWindowContainerToken = setUpTaskWindowContainerToken(mVirtualDisplayContent);
        mTaskSession = ContentRecordingSession.createTaskSession(sTaskWindowContainerToken);
        mTaskSession.setVirtualDisplayId(displayId);

        // GIVEN a session is waiting for the user to review consent.
        mWaitingDisplaySession.setVirtualDisplayId(displayId);
        mWaitingDisplaySession.setWaitingForConsent(true);

        // Skip unnecessary operations of relayout.
        spyOn(mWm.mWindowPlacerLocked);
        doNothing().when(mWm.mWindowPlacerLocked).performSurfacePlacement(anyBoolean());
    }

    @Test
    public void testIsCurrentlyRecording() {
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();

        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_display() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testUpdateRecording_display_invalidDisplayIdToMirror() {
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                INVALID_DISPLAY);
        mContentRecorder.setContentRecordingSession(session);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_display_noDisplayContentToMirror() {
        doReturn(null).when(
                mWm.mRoot).getDisplayContent(anyInt());
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testUpdateRecording_task_nullToken() {
        ContentRecordingSession session = mTaskSession;
        session.setVirtualDisplayId(mDisplaySession.getVirtualDisplayId());
        session.setTokenToRecord(null);
        mContentRecorder.setContentRecordingSession(session);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
        verify(mMediaProjectionManagerWrapper).stopActiveProjection();
    }

    @Test
    public void testUpdateRecording_task_noWindowContainer() {
        // Use the window container token of the DisplayContent, rather than task.
        ContentRecordingSession invalidTaskSession = ContentRecordingSession.createTaskSession(
                new WindowContainer.RemoteToken(mDisplayContent));
        mContentRecorder.setContentRecordingSession(invalidTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
        verify(mMediaProjectionManagerWrapper).stopActiveProjection();
    }

    @Test
    public void testUpdateRecording_wasPaused() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        mContentRecorder.pauseRecording();
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testUpdateRecording_waitingForConsent() {
        mContentRecorder.setContentRecordingSession(mWaitingDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();


        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testOnConfigurationChanged_neverRecording() {
        mContentRecorder.onConfigurationChanged(ORIENTATION_PORTRAIT);

        verify(mTransaction, never()).setPosition(eq(mRecordedSurface), anyFloat(), anyFloat());
        verify(mTransaction, never()).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testOnConfigurationChanged_resizesSurface() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        // Ensure a different orientation when we check if something has changed.
        @Configuration.Orientation final int lastOrientation =
                mDisplayContent.getConfiguration().orientation == ORIENTATION_PORTRAIT
                        ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
        mContentRecorder.onConfigurationChanged(lastOrientation);

        verify(mTransaction, atLeast(2)).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction, atLeast(2)).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testOnConfigurationChanged_resizesVirtualDisplay() {
        final int newWidth = 55;
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        // The user rotates the device, so the host app resizes the virtual display for the capture.
        resizeDisplay(mDisplayContent, newWidth, sSurfaceSize.y);
        resizeDisplay(mVirtualDisplayContent, newWidth, sSurfaceSize.y);
        mContentRecorder.onConfigurationChanged(mDisplayContent.getConfiguration().orientation);

        verify(mTransaction, atLeast(2)).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction, atLeast(2)).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testOnConfigurationChanged_rotateVirtualDisplay() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        // Change a value that we shouldn't rely upon; it has the wrong type.
        mVirtualDisplayContent.setOverrideOrientation(SCREEN_ORIENTATION_FULL_SENSOR);
        mContentRecorder.onConfigurationChanged(
                mVirtualDisplayContent.getConfiguration().orientation);

        // No resize is issued, only the initial transformations when we started recording.
        verify(mTransaction).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    /**
     * Test that resizing the output surface results in resizing the mirrored content to fit.
     */
    @Test
    public void testOnConfigurationChanged_resizeSurface() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        // Resize the output surface.
        final Point newSurfaceSize = new Point(Math.round(sSurfaceSize.x / 2f),
                Math.round(sSurfaceSize.y * 2));
        doReturn(newSurfaceSize).when(mWm.mDisplayManagerInternal).getDisplaySurfaceDefaultSize(
                anyInt());
        mContentRecorder.onConfigurationChanged(
                mVirtualDisplayContent.getConfiguration().orientation);

        // No resize is issued, only the initial transformations when we started recording.
        verify(mTransaction, atLeast(2)).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction, atLeast(2)).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());

    }

    @Test
    public void testOnTaskOrientationConfigurationChanged_resizesSurface() {
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();

        Configuration config = mTask.getConfiguration();
        // Ensure a different orientation when we compare.
        @Configuration.Orientation final int orientation =
                config.orientation == ORIENTATION_PORTRAIT ? ORIENTATION_LANDSCAPE
                        : ORIENTATION_PORTRAIT;
        final Rect lastBounds = config.windowConfiguration.getBounds();
        config.orientation = orientation;
        config.windowConfiguration.setBounds(
                new Rect(0, 0, lastBounds.height(), lastBounds.width()));
        mTask.onConfigurationChanged(config);

        verify(mTransaction, atLeast(2)).setPosition(eq(mRecordedSurface), anyFloat(),
                anyFloat());
        verify(mTransaction, atLeast(2)).setMatrix(eq(mRecordedSurface), anyFloat(), anyFloat(),
                anyFloat(), anyFloat());
    }

    @Test
    public void testOnTaskBoundsConfigurationChanged_notifiesCallback() {
        mTask.getRootTask().setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);

        final int minWidth = 222;
        final int minHeight = 777;
        final int recordedWidth = 333;
        final int recordedHeight = 999;

        final ActivityInfo info = new ActivityInfo();
        info.windowLayout = new ActivityInfo.WindowLayout(-1 /* width */,
                -1 /* widthFraction */, -1 /* height */, -1 /* heightFraction */,
                Gravity.NO_GRAVITY, minWidth, minHeight);
        mTask.setMinDimensions(info);

        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // WHEN a configuration change arrives, and the recorded content is a different size.
        Configuration configuration = mTask.getConfiguration();
        configuration.windowConfiguration.setBounds(new Rect(0, 0, recordedWidth, recordedHeight));
        configuration.windowConfiguration.setAppBounds(
                new Rect(0, 0, recordedWidth, recordedHeight));
        mTask.onConfigurationChanged(configuration);
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN content in the captured DisplayArea is scaled to fit the surface size.
        verify(mTransaction, atLeastOnce()).setMatrix(eq(mRecordedSurface), anyFloat(), eq(0f),
                eq(0f),
                anyFloat());
        // THEN the resize callback is notified.
        verify(mMediaProjectionManagerWrapper).notifyActiveProjectionCapturedContentResized(
                recordedWidth, recordedHeight);
    }

    @Test
    public void testTaskWindowingModeChanged_pip_stopsRecording() {
        // WHEN a recording is ongoing.
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // WHEN a configuration change arrives, and the task is now pinned.
        mTask.setWindowingMode(WINDOWING_MODE_PINNED);
        Configuration configuration = mTask.getConfiguration();
        mTask.onConfigurationChanged(configuration);

        // THEN recording is paused.
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testTaskWindowingModeChanged_fullscreen_startsRecording() {
        // WHEN a recording is ongoing.
        mTask.setWindowingMode(WINDOWING_MODE_PINNED);
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();

        // WHEN the task is now fullscreen.
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        mContentRecorder.updateRecording();

        // THEN recording is started.
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testStartRecording_notifiesCallback_taskSession() {
        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper)
                .notifyActiveProjectionCapturedContentVisibilityChanged(true);
    }

    @Test
    public void testStartRecording_notifiesCallback_displaySession() {
        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper)
                .notifyActiveProjectionCapturedContentVisibilityChanged(true);
    }

    @Test
    public void testStartRecording_taskInPIP_recordingNotStarted() {
        // GIVEN a task is in PIP.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mTask.setWindowingMode(WINDOWING_MODE_PINNED);

        // WHEN a recording tries to start.
        mContentRecorder.updateRecording();

        // THEN recording does not start.
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testStartRecording_taskInSplit_recordingStarted() {
        // GIVEN a task is in PIP.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mTask.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);

        // WHEN a recording tries to start.
        mContentRecorder.updateRecording();

        // THEN recording does not start.
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testStartRecording_taskInFullscreen_recordingStarted() {
        // GIVEN a task is in PIP.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        // WHEN a recording tries to start.
        mContentRecorder.updateRecording();

        // THEN recording does not start.
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();
    }

    @Test
    public void testOnVisibleRequestedChanged_notifiesCallback() {
        // WHEN a recording is ongoing.
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // WHEN the child requests a visibility change.
        boolean isVisibleRequested = true;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper, atLeastOnce())
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);

        // WHEN the child requests a visibility change.
        isVisibleRequested = false;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is notified.
        verify(mMediaProjectionManagerWrapper)
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);
    }

    @Test
    public void testOnVisibleRequestedChanged_noRecording_doesNotNotifyCallback() {
        // WHEN a recording is not ongoing.
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();

        // WHEN the child requests a visibility change.
        boolean isVisibleRequested = true;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is not notified.
        verify(mMediaProjectionManagerWrapper, never())
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);

        // WHEN the child requests a visibility change.
        isVisibleRequested = false;
        mContentRecorder.onVisibleRequestedChanged(isVisibleRequested);

        // THEN the visibility change callback is not notified.
        verify(mMediaProjectionManagerWrapper, never())
                .notifyActiveProjectionCapturedContentVisibilityChanged(isVisibleRequested);
    }

    @Test
    public void testPauseRecording_pausesRecording() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        mContentRecorder.pauseRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testPauseRecording_neverRecording() {
        mContentRecorder.pauseRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testStopRecording_stopsRecording() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();

        mContentRecorder.stopRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testStopRecording_neverRecording() {
        mContentRecorder.stopRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isFalse();
    }

    @Test
    public void testRemoveTask_stopsRecording() {
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();

        mTask.removeImmediately();

        verify(mMediaProjectionManagerWrapper).stopActiveProjection();
    }

    @Test
    public void testRemoveTask_stopsRecording_nullSessionShouldNotThrowExceptions() {
        mContentRecorder.setContentRecordingSession(mTaskSession);
        mContentRecorder.updateRecording();
        mContentRecorder.setContentRecordingSession(null);
        mTask.removeImmediately();
    }

    @Test
    public void testUpdateMirroredSurface_capturedAreaResized() {
        mContentRecorder.setContentRecordingSession(mDisplaySession);
        mContentRecorder.updateRecording();
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // WHEN attempting to mirror on the virtual display, and the captured content is resized.
        float xScale = 0.7f;
        float yScale = 2f;
        Rect displayAreaBounds = new Rect(0, 0, Math.round(sSurfaceSize.x * xScale),
                Math.round(sSurfaceSize.y * yScale));
        mContentRecorder.updateMirroredSurface(mTransaction, displayAreaBounds, sSurfaceSize);
        assertThat(mContentRecorder.isCurrentlyRecording()).isTrue();

        // THEN content in the captured DisplayArea is scaled to fit the surface size.
        verify(mTransaction, atLeastOnce()).setMatrix(mRecordedSurface, 1.0f / yScale, 0, 0,
                1.0f / yScale);
        // THEN captured content is positioned in the centre of the output surface.
        int scaledWidth = Math.round((float) displayAreaBounds.width() / xScale);
        int xInset = (sSurfaceSize.x - scaledWidth) / 2;
        verify(mTransaction, atLeastOnce()).setPosition(mRecordedSurface, xInset, 0);
        // THEN the resize callback is notified.
        verify(mMediaProjectionManagerWrapper).notifyActiveProjectionCapturedContentResized(
                displayAreaBounds.width(), displayAreaBounds.height());
    }

    /**
     * Creates a {@link android.window.WindowContainerToken} associated with a task, in order for
     * that task to be recorded.
     */
    private IBinder setUpTaskWindowContainerToken(DisplayContent displayContent) {
        final Task rootTask = createTask(displayContent);
        mTask = createTaskInRootTask(rootTask, 0 /* userId */);
        // Ensure the task is not empty.
        createActivityRecord(displayContent, mTask);
        return mTask.getTaskInfo().token.asBinder();
    }

    /**
     * SurfaceControl successfully creates a mirrored surface of the given size.
     */
    private SurfaceControl surfaceControlMirrors(Point surfaceSize) {
        // Do not set the parent, since the mirrored surface is the root of a new surface hierarchy.
        SurfaceControl mirroredSurface = new SurfaceControl.Builder()
                .setName("mirroredSurface")
                .setBufferSize(surfaceSize.x, surfaceSize.y)
                .setCallsite("mirrorSurface")
                .build();
        doReturn(mirroredSurface).when(() -> SurfaceControl.mirrorSurface(any()));
        doReturn(surfaceSize).when(mWm.mDisplayManagerInternal).getDisplaySurfaceDefaultSize(
                anyInt());
        return mirroredSurface;
    }
}
