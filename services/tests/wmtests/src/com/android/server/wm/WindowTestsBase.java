/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.WindowManager;

import com.android.server.AttributeCache;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;

import java.util.HashSet;
import java.util.LinkedList;

/**
 * Common base class for window manager unit test classes.
 *
 * Make sure any requests to WM hold the WM lock if needed b/73966377
 */
class WindowTestsBase {
    private static final String TAG = WindowTestsBase.class.getSimpleName();

    WindowManagerService mWm;
    private final IWindow mIWindow = new TestIWindow();
    private Session mMockSession;
    // The default display is removed in {@link #setUp} and then we iterate over all displays to
    // make sure we don't collide with any existing display. If we run into no other display, the
    // added display should be treated as default. This cannot be the default display
    private static int sNextDisplayId = DEFAULT_DISPLAY + 1;
    static int sNextStackId = 1000;

    /** Non-default display. */
    DisplayContent mDisplayContent;
    DisplayInfo mDisplayInfo = new DisplayInfo();
    WindowState mWallpaperWindow;
    WindowState mImeWindow;
    WindowState mImeDialogWindow;
    WindowState mStatusBarWindow;
    WindowState mDockedDividerWindow;
    WindowState mNavBarWindow;
    WindowState mAppWindow;
    WindowState mChildAppWindowAbove;
    WindowState mChildAppWindowBelow;
    HashSet<WindowState> mCommonWindows;

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Rule
    public final WindowManagerServiceRule mWmRule = new WindowManagerServiceRule();

    static WindowState.PowerManagerWrapper sPowerManagerWrapper;  // TODO(roosa): make non-static.

    @BeforeClass
    public static void setUpOnceBase() {
        AttributeCache.init(getInstrumentation().getTargetContext());
        sPowerManagerWrapper = mock(WindowState.PowerManagerWrapper.class);
    }

    @Before
    public void setUpBase() {
        // If @Before throws an exception, the error isn't logged. This will make sure any failures
        // in the set up are clear. This can be removed when b/37850063 is fixed.
        try {
            mMockSession = mock(Session.class);

            final Context context = getInstrumentation().getTargetContext();

            mWm = mWmRule.getWindowManagerService();
            beforeCreateDisplay();

            context.getDisplay().getDisplayInfo(mDisplayInfo);
            mDisplayContent = createNewDisplay();
            mWm.mDisplayEnabled = true;
            mWm.mDisplayReady = true;

            // Set-up some common windows.
            mCommonWindows = new HashSet<>();
            synchronized (mWm.mGlobalLock) {
                mWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
                mImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "mImeWindow");
                mDisplayContent.mInputMethodWindow = mImeWindow;
                mImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG,
                        "mImeDialogWindow");
                mStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "mStatusBarWindow");
                mNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "mNavBarWindow");
                mDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER,
                        "mDockedDividerWindow");
                mAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "mAppWindow");
                mChildAppWindowAbove = createCommonWindow(mAppWindow,
                        TYPE_APPLICATION_ATTACHED_DIALOG,
                        "mChildAppWindowAbove");
                mChildAppWindowBelow = createCommonWindow(mAppWindow,
                        TYPE_APPLICATION_MEDIA_OVERLAY,
                        "mChildAppWindowBelow");
            }
            // Adding a display will cause freezing the display. Make sure to wait until it's
            // unfrozen to not run into race conditions with the tests.
            waitUntilHandlersIdle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up test", e);
            throw e;
        }
    }

    void beforeCreateDisplay() {
        // Called before display is created.
    }

    @After
    public void tearDownBase() {
        // If @After throws an exception, the error isn't logged. This will make sure any failures
        // in the tear down are clear. This can be removed when b/37850063 is fixed.
        try {
            // Test may schedule to perform surface placement or other messages. Wait until a
            // stable state to clean up for consistency.
            waitUntilHandlersIdle();

            final LinkedList<WindowState> nonCommonWindows = new LinkedList<>();

            synchronized (mWm.mGlobalLock) {
                mWm.mRoot.forAllWindows(w -> {
                    if (!mCommonWindows.contains(w)) {
                        nonCommonWindows.addLast(w);
                    }
                }, true /* traverseTopToBottom */);

                while (!nonCommonWindows.isEmpty()) {
                    nonCommonWindows.pollLast().removeImmediately();
                }

                for (int i = mWm.mRoot.mChildren.size() - 1; i >= 0; --i) {
                    final DisplayContent displayContent = mWm.mRoot.mChildren.get(i);
                    if (!displayContent.isDefaultDisplay) {
                        displayContent.removeImmediately();
                    }
                }
                // Remove app transition & window freeze timeout callbacks to prevent unnecessary
                // actions after test.
                mWm.getDefaultDisplayContentLocked().mAppTransition
                        .removeAppTransitionTimeoutCallbacks();
                mWm.mH.removeMessages(WindowManagerService.H.WINDOW_FREEZE_TIMEOUT);
                mDisplayContent.mInputMethodTarget = null;
            }

            // Wait until everything is really cleaned up.
            waitUntilHandlersIdle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to tear down test", e);
            throw e;
        }
    }

    private WindowState createCommonWindow(WindowState parent, int type, String name) {
        synchronized (mWm.mGlobalLock) {
            final WindowState win = createWindow(parent, type, name);
            mCommonWindows.add(win);
            // Prevent common windows from been IMe targets
            win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
            return win;
        }
    }

    /**
     * Waits until the main handler for WM has processed all messages.
     */
    void waitUntilHandlersIdle() {
        mWmRule.waitUntilWindowManagerHandlersIdle();
    }

    private WindowToken createWindowToken(
            DisplayContent dc, int windowingMode, int activityType, int type) {
        synchronized (mWm.mGlobalLock) {
            if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
                return WindowTestUtils.createTestWindowToken(type, dc);
            }

            return createAppWindowToken(dc, windowingMode, activityType);
        }
    }

    AppWindowToken createAppWindowToken(DisplayContent dc, int windowingMode, int activityType) {
        return createTestAppWindowToken(dc, windowingMode, activityType);
    }

    WindowTestUtils.TestAppWindowToken createTestAppWindowToken(DisplayContent dc, int
            windowingMode, int activityType) {
        final TaskStack stack = createStackControllerOnStackOnDisplay(windowingMode, activityType,
                dc).mContainer;
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken appWindowToken =
                WindowTestUtils.createTestAppWindowToken(dc);
        task.addChild(appWindowToken, 0);
        return appWindowToken;
    }

    WindowState createWindow(WindowState parent, int type, String name) {
        synchronized (mWm.mGlobalLock) {
            return (parent == null)
                    ? createWindow(parent, type, mDisplayContent, name)
                    : createWindow(parent, type, parent.mToken, name);
        }
    }

    WindowState createWindowOnStack(WindowState parent, int windowingMode, int activityType,
            int type, DisplayContent dc, String name) {
        synchronized (mWm.mGlobalLock) {
            final WindowToken token = createWindowToken(dc, windowingMode, activityType, type);
            return createWindow(parent, type, token, name);
        }
    }

    WindowState createAppWindow(Task task, int type, String name) {
        synchronized (mWm.mGlobalLock) {
            final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
            task.addChild(token, 0);
            return createWindow(null, type, token, name);
        }
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        synchronized (mWm.mGlobalLock) {
            final WindowToken token = createWindowToken(
                    dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
            return createWindow(parent, type, token, name);
        }
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        synchronized (mWm.mGlobalLock) {
            final WindowToken token = createWindowToken(
                    dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
            return createWindow(parent, type, token, name, 0 /* ownerId */,
                    ownerCanAddInternalSystemWindow);
        }
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        synchronized (mWm.mGlobalLock) {
            return createWindow(parent, type, token, name, 0 /* ownerId */,
                    false /* ownerCanAddInternalSystemWindow */);
        }
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow) {
        return createWindow(parent, type, token, name, ownerId, ownerCanAddInternalSystemWindow,
                mWm, mMockSession, mIWindow);
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token,
            String name, int ownerId, boolean ownerCanAddInternalSystemWindow,
            WindowManagerService service, Session session, IWindow iWindow) {
        synchronized (service.mGlobalLock) {
            final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
            attrs.setTitle(name);

            final WindowState w = new WindowState(service, session, iWindow, token, parent,
                    OP_NONE,
                    0, attrs, VISIBLE, ownerId, ownerCanAddInternalSystemWindow,
                    sPowerManagerWrapper);
            // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
            // adding it to the token...
            token.addWindow(w);
            return w;
        }
    }

    /** Creates a {@link TaskStack} and adds it to the specified {@link DisplayContent}. */
    TaskStack createTaskStackOnDisplay(DisplayContent dc) {
        synchronized (mWm.mGlobalLock) {
            return createStackControllerOnDisplay(dc).mContainer;
        }
    }

    StackWindowController createStackControllerOnDisplay(DisplayContent dc) {
        synchronized (mWm.mGlobalLock) {
            return createStackControllerOnStackOnDisplay(
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, dc);
        }
    }

    StackWindowController createStackControllerOnStackOnDisplay(
            int windowingMode, int activityType, DisplayContent dc) {
        synchronized (mWm.mGlobalLock) {
            final Configuration overrideConfig = new Configuration();
            overrideConfig.windowConfiguration.setWindowingMode(windowingMode);
            overrideConfig.windowConfiguration.setActivityType(activityType);
            final int stackId = ++sNextStackId;
            final StackWindowController controller = new StackWindowController(stackId, null,
                    dc.getDisplayId(), true /* onTop */, new Rect(), mWm);
            controller.onRequestedOverrideConfigurationChanged(overrideConfig);
            return controller;
        }
    }

    /** Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    Task createTaskInStack(TaskStack stack, int userId) {
        return WindowTestUtils.createTaskInStack(mWm, stack, userId);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    DisplayContent createNewDisplay() {
        return createNewDisplay(mDisplayInfo);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    DisplayContent createNewDisplay(DisplayInfo displayInfo) {
        final int displayId = sNextDisplayId++;
        final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                displayInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
        synchronized (mWm.mGlobalLock) {
            return new DisplayContent(display, mWm, mock(DisplayWindowController.class));
        }
    }

    /**
     * Creates a {@link DisplayContent} with given display state and adds it to the system.
     *
     * Unlike {@link #createNewDisplay()} that uses a mock {@link DisplayWindowController} to
     * initialize {@link DisplayContent}, this method used real controller object when the test
     * need to verify its related flows.
     *
     * @param displayState For initializing the state of the display. See
     *                     {@link Display#getState()}.
     */
    DisplayContent createNewDisplayWithController(int displayState) {
        // Leverage main display info & initialize it with display state for given displayId.
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.state = displayState;
        final int displayId = sNextDisplayId++;
        final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                displayInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
        final DisplayWindowController dcw = new DisplayWindowController(display, mWm);
        synchronized (mWm.mGlobalLock) {
            // Display creation is driven by DisplayWindowController via ActivityStackSupervisor.
            // We skip those steps here.
            return mWm.mRoot.createDisplayContent(display, dcw);
        }
    }

    /** Creates a {@link com.android.server.wm.WindowTestUtils.TestWindowState} */
    WindowTestUtils.TestWindowState createWindowState(WindowManager.LayoutParams attrs,
            WindowToken token) {
        synchronized (mWm.mGlobalLock) {
            return new WindowTestUtils.TestWindowState(mWm, mMockSession, mIWindow, attrs, token);
        }
    }
}
