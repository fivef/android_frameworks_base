/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;

import android.animation.ObjectAnimator;
import android.app.ActivityManagerNative;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Xfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import static com.android.internal.util.jellybam.AwesomeConstants.*;
import com.android.internal.util.jellybam.LockScreenHelpers;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.widget.multiwaveview.TargetDrawable;
import com.android.internal.R;
import com.android.internal.widget.multiwaveview.TargetDrawable;

import java.util.ArrayList;

public class KeyguardSelectorView extends LinearLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";

    private KeyguardSecurityCallback mCallback;
    private KeyguardTargets mTargets;
    private GlowPadView mGlowPadView;
    private ObjectAnimator mAnim;
    private View mFadeView;
    private boolean mIsBouncing;
    private LockPatternUtils mLockPatternUtils;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private Drawable mBouncerFrame;
    private String[] mStoredTargets;
    private int mTargetOffset;
    private boolean mIsScreenLarge;
    private int mCreationOrientation;
    private Resources res;

    private boolean mGlowPadLock;
    private boolean mBoolLongPress;
    private int mTarget;
    private boolean mLongPress = false;
    private boolean mUsesCustomTargets;
    private String[] targetActivities = new String[8];
    private String[] longActivities = new String[8];
    private String[] customIcons = new String[8];

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
            }
        }
    }
    private H mHandler = new H();

    private void launchAction(String action) {
        try {
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException ignored) {
        }

        AwesomeConstant AwesomeEnum = fromString(action);
        switch (AwesomeEnum) {
        case ACTION_UNLOCK:
            mCallback.userActivity(0);
            mCallback.dismiss(false);
            break;
        case ACTION_ASSIST:
            Intent assistIntent =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, UserHandle.USER_CURRENT);
                if (assistIntent != null) {
                    mActivityLauncher.launchActivity(assistIntent, false, true, null, null);
                } else {
                    Log.w(TAG, "Failed to get intent for assist activity");
                }
                mCallback.userActivity(0);
                break;
        case ACTION_CAMERA:
            mActivityLauncher.launchCamera(null, null);
            mCallback.userActivity(0);
            break;
        case ACTION_APP:
            Intent i = new Intent();
            i.setAction("com.android.systemui.aokp.LAUNCH_ACTION");
            i.putExtra("action", action);
            mContext.sendBroadcastAsUser(i, UserHandle.ALL);
            mCallback.userActivity(0);
            break;
        }
    }

    OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

       final Runnable SetLongPress = new Runnable () {
            public void run() {
                if (!mGlowPadLock) {
                    mGlowPadLock = true;
                    mLongPress = true;
                    launchAction(longActivities[mTarget]);
                 }
            }
        };

        public void onTrigger(View v, int target) {
            if (!mUsesCustomTargets) {
                mCallback.userActivity(0);
                mCallback.dismiss(false);
            } else {
                if (!mLongPress) {
                    mHandler.removeCallbacks(SetLongPress);
                    launchAction(targetActivities[target]);
                }
            }
        }

        public void onReleased(View v, int handle) {
            if (!mIsBouncing) {
                doTransition(mFadeView, 1.0f);
            }
        }

        public void onGrabbed(View v, int handle) {
            mCallback.userActivity(0);
            doTransition(mFadeView, 0.0f);
        }

        public void onGrabbedStateChange(View v, int handle) {
            mHandler.removeCallbacks(SetLongPress);
            mLongPress = false;
        }

        public void onTargetChange(View v, int target) {
            if (target == -1) {
                mHandler.removeCallbacks(SetLongPress);
                mLongPress = false;
            } else {
                if (mBoolLongPress && !TextUtils.isEmpty(longActivities[target]) && !longActivities[target].equals(AwesomeConstant.ACTION_NULL.value())) {
                    mTarget = target;
                    mHandler.postDelayed(SetLongPress, ViewConfiguration.getLongPressTimeout());
                }
            }
        }

        public void onFinishFinalAnimation() {

        }

    };

    KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onDevicePolicyManagerStateChanged() {
            updateTargets();
        }

        @Override
        public void onSimStateChanged(State simState) {
            updateTargets();
        }
    };

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        @Override
        Context getContext() {
            return mContext;
        }};

    public KeyguardSelectorView(Context context) {
        this(context, null);
        mCreationOrientation = Resources.getSystem().getConfiguration().orientation;
    }

    public KeyguardSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        res = getResources();
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        updateTargets();

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        View bouncerFrameView = findViewById(R.id.keyguard_selector_view_frame);
        mBouncerFrame = bouncerFrameView.getBackground();
    }

    public void setCarrierArea(View carrierArea) {
        mFadeView = carrierArea;
    }

    public boolean isScreenLarge() {
        final int screenSize = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        boolean isScreenLarge = screenSize == Configuration.SCREENLAYOUT_SIZE_LARGE ||
                screenSize == Configuration.SCREENLAYOUT_SIZE_XLARGE;
        return isScreenLarge;
    }

    private StateListDrawable getLayeredDrawable(Drawable back, Drawable front, int inset, boolean frontBlank) {
        Resources res = getResources();
        InsetDrawable[] inactivelayer = new InsetDrawable[2];
        InsetDrawable[] activelayer = new InsetDrawable[2];
        inactivelayer[0] = new InsetDrawable(res.getDrawable(com.android.internal.R.drawable.ic_lockscreen_lock_pressed), 0, 0, 0, 0);
        inactivelayer[1] = new InsetDrawable(front, inset, inset, inset, inset);
        activelayer[0] = new InsetDrawable(back, 0, 0, 0, 0);
        activelayer[1] = new InsetDrawable(frontBlank ? res.getDrawable(android.R.color.transparent) : front, inset, inset, inset, inset);
        StateListDrawable states = new StateListDrawable();
        LayerDrawable inactiveLayerDrawable = new LayerDrawable(inactivelayer);
        inactiveLayerDrawable.setId(0, 0);
        inactiveLayerDrawable.setId(1, 1);
        LayerDrawable activeLayerDrawable = new LayerDrawable(activelayer);
        activeLayerDrawable.setId(0, 0);
        activeLayerDrawable.setId(1, 1);
        states.addState(TargetDrawable.STATE_INACTIVE, inactiveLayerDrawable);
        states.addState(TargetDrawable.STATE_ACTIVE, activeLayerDrawable);
        states.addState(TargetDrawable.STATE_FOCUSED, activeLayerDrawable);
        return states;
    }

    public boolean isTargetPresent(int resId) {
        return mGlowPadView.getTargetPosition(resId) != -1;
    }

    @Override
    public void showUsabilityHint() {
        mGlowPadView.ping();
    }

    public boolean isScreenPortrait() {
        return res.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private void updateTargets() {
        mLongPress = false;
        mGlowPadLock = false;
        mUsesCustomTargets = mUnlockCounter() != 0;
        ArrayList<TargetDrawable> storedDraw = new ArrayList<TargetDrawable>();

        for (int i = 0; i < 8; i++) {
            targetActivities[i] = Settings.System.getString(
                    mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_SHORT[i]);
            longActivities[i] = Settings.System.getString(
                    mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_LONG[i]);
            customIcons[i] = Settings.System.getString(
                    mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_ICON[i]);
        }

        mBoolLongPress = Settings.System.getBoolean(
              mContext.getContentResolver(), Settings.System.LOCKSCREEN_TARGETS_LONGPRESS, false);

       if (!TextUtils.isEmpty(targetActivities[5]) || !TextUtils.isEmpty(targetActivities[6]) || !TextUtils.isEmpty(targetActivities[7])) {
           Resources res = getResources();
           LinearLayout glowPadContainer = (LinearLayout) findViewById(R.id.keyguard_glow_pad_container);
           if (glowPadContainer != null && isScreenPortrait()) {
               FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                   FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
               int pxBottom = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, res.getDisplayMetrics());
               params.setMargins(0, 0, 0, -pxBottom);
               glowPadContainer.setLayoutParams(params);
           }
       }

        // no targets? add just an unlock.
        if (!mUsesCustomTargets) {
            storedDraw.add(LockScreenHelpers.getTargetDrawable(mContext, AwesomeConstant.ACTION_UNLOCK.value()));
        } else {
            // Add The Target actions and Icons
            for (int i = 0; i < 8 ; i++) {
                if (!TextUtils.isEmpty(customIcons[i])) {
                    storedDraw.add(LockScreenHelpers.getCustomDrawable(mContext, customIcons[i]));
                } else {
                    storedDraw.add(LockScreenHelpers.getTargetDrawable(mContext, targetActivities[i]));
                }
            }
        }
        mGlowPadView.setTargetResources(storedDraw);
        updateResources();
    }

    private int mUnlockCounter() {
        int counter = 0;
        for (int i = 0; i < 8 ; i++) {
            if (!TextUtils.isEmpty(targetActivities[i])) {
                if (targetActivities[i].equals(AwesomeConstant.ACTION_UNLOCK.value())) {
                    counter += 1;
                }
            }
        }
        return counter;
    }

    public void updateResources() {
        // Update the search icon with drawable from the search .apk
        Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
               .getAssistIntent(mContext, UserHandle.USER_CURRENT);
        if (intent != null) {
            ComponentName component = intent.getComponent();
            boolean replaced = mGlowPadView.replaceTargetDrawablesIfPresent(component,
                    ASSIST_ICON_METADATA_NAME + "_google",
                    com.android.internal.R.drawable.ic_action_assist_generic);
            if (!replaced && !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                        ASSIST_ICON_METADATA_NAME,
                            com.android.internal.R.drawable.ic_action_assist_generic)) {
                Slog.w(TAG, "Couldn't grab icon from package " + component);
            }
            mGlowPadView.setTargetResources(storedDraw);
        }
    }

    public static Bitmap getRoundedCornerBitmap(Bitmap bitmap) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
            bitmap.getHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final int color = 0xff424242;
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        final RectF rectF = new RectF(rect);
        final float roundPx = 24;
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(color);
        canvas.drawRoundRect(rectF, roundPx, roundPx, paint);
        paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }

    void doTransition(View view, float to) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        mAnim.start();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
        mTargets = (KeyguardTargets) findViewById(R.id.targets);
        if(mTargets != null) {
            mTargets.setKeyguardCallback(callback);
        }
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {
        mGlowPadView.reset(false);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        KeyguardUpdateMonitor.getInstance(getContext()).removeCallback(mInfoCallback);
    }

    @Override
    public void onResume(int reason) {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mInfoCallback);
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        mIsBouncing = true;
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        mIsBouncing = false;
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }
}
