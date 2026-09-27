/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.settings.password;

import static com.android.settings.password.ChooseLockSettingsHelper.EXTRA_KEY_UNIFICATION_PROFILE_CREDENTIAL;
import static com.android.settings.password.ChooseLockSettingsHelper.EXTRA_KEY_UNIFICATION_PROFILE_ID;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.SetupWizardUtils;
import com.android.settings.Utils;
import com.android.settings.notification.RedactionInterstitial;
import com.android.settingslib.core.instrumentation.InstrumentedFragment;
import com.google.android.setupdesign.GlifLayout;
import com.google.android.setupdesign.util.ThemeHelper;

/**
 * Enrollment for a Knock Code.
 *
 * <p>The user taps a sequence on a 2x2 grid, twice, and that sequence becomes their screen lock.
 * No new credential type is introduced: {@link KnockCodePadView} turns the taps into a numeric
 * string and this screen hands that string to {@link LockscreenCredential#createPin}, so from the
 * platform's point of view the result is an ordinary PIN. That buys hardware-backed verification,
 * gatekeeper throttling and failed-attempt accounting without touching
 * {@code LockSettingsService} or {@code SyntheticPasswordManager}.
 *
 * <p>The consequences of that choice are worth stating plainly. A Knock Code cannot coexist with a
 * separate PIN, because it <em>is</em> the PIN. And its entropy is low - four taps is 256
 * combinations - so the protection rests on gatekeeper's throttling rather than on the code itself,
 * which is the same position LG's implementation was in.
 *
 * <p>Two markers are stored alongside the credential, both in {@link LockPatternUtils}:
 * {@code knock_code_enabled}, which is what makes the lock screen show the tap grid instead of a
 * keypad, and {@code knock_code_length}, without which the lock screen could not know when the user
 * has finished tapping, since a Knock Code has no confirm key.
 *
 * <p>Nothing here turns the feature off. Choosing any other lock type in
 * {@link ChooseLockGeneric} replaces the credential, and
 * {@link LockPatternUtils#setLockCredential} clears the marker as part of that write - so the
 * picker entry is both the on switch and, indirectly, the off switch.
 */
public class ChooseLockKnockCode extends SettingsActivity {

    @Override
    public Intent getIntent() {
        final Intent modIntent = new Intent(super.getIntent());
        modIntent.putExtra(EXTRA_SHOW_FRAGMENT, getFragmentClass().getName());
        modIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_USE_EXPRESSIVE_STYLE,
                ThemeHelper.shouldApplyGlifExpressiveStyle(getApplicationContext()));
        return modIntent;
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        return ChooseLockKnockCodeFragment.class.getName().equals(fragmentName);
    }

    @Override
    protected boolean isToolbarEnabled() {
        return false;
    }

    /* package */ Class<? extends Fragment> getFragmentClass() {
        return ChooseLockKnockCodeFragment.class;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(SetupWizardUtils.getTheme(this, getIntent()));
        ThemeHelper.trySetDynamicColor(this);
        if (ThemeHelper.shouldApplyGlifExpressiveStyle(getApplicationContext())) {
            ThemeHelper.trySetSuwTheme(this);
        }
        super.onCreate(savedInstanceState);
        findViewById(R.id.content_parent).setFitsSystemWindows(false);
        // The code is entered straight onto the screen, so keep it out of screenshots and out of
        // the recents thumbnail.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    public static class IntentBuilder {

        private final Intent mIntent;

        public IntentBuilder(Context context) {
            mIntent = new Intent(context, ChooseLockKnockCode.class);
            mIntent.putExtra(ChooseLockGeneric.CONFIRM_CREDENTIALS, false);
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_USE_EXPRESSIVE_STYLE,
                    ThemeHelper.shouldApplyGlifExpressiveStyle(context));
            // A Knock Code is verified as a PIN, so it carries PIN's quality. This is what the
            // platform, DevicePolicyManager and the encryption reporting all see.
            mIntent.putExtra(LockPatternUtils.PASSWORD_TYPE_KEY,
                    DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
        }

        public IntentBuilder setUserId(int userId) {
            mIntent.putExtra(Intent.EXTRA_USER_ID, userId);
            return this;
        }

        public IntentBuilder setPassword(LockscreenCredential password) {
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_PASSWORD, password);
            return this;
        }

        public IntentBuilder setRequestGatekeeperPasswordHandle(
                boolean requestGatekeeperPasswordHandle) {
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_REQUEST_GK_PW_HANDLE,
                    requestGatekeeperPasswordHandle);
            return this;
        }

        /**
         * Configures the launch such that at the end of enrollment, the managed profile
         * {@code profileId} has its lock screen unified to the parent user. The profile's current
         * credential must be supplied as {@code credential}.
         */
        public IntentBuilder setProfileToUnify(int profileId, LockscreenCredential credential) {
            mIntent.putExtra(EXTRA_KEY_UNIFICATION_PROFILE_ID, profileId);
            mIntent.putExtra(EXTRA_KEY_UNIFICATION_PROFILE_CREDENTIAL, credential);
            return this;
        }

        public Intent build() {
            return mIntent;
        }
    }

    public static class ChooseLockKnockCodeFragment extends InstrumentedFragment
            implements SaveAndFinishWorker.Listener {

        private static final String KEY_FIRST_SEQUENCE = "first_sequence";
        private static final String KEY_UI_STAGE = "ui_stage";
        private static final String FRAGMENT_TAG_SAVE_AND_FINISH = "save_and_finish_worker";

        static final int RESULT_FINISHED = Activity.RESULT_FIRST_USER;

        /** Where the user is in the enter-it-twice flow. */
        private enum Stage { Introduction, NeedToConfirm, ConfirmWrong }

        private LockPatternUtils mLockPatternUtils;
        private SaveAndFinishWorker mSaveAndFinishWorker;
        private LockscreenCredential mCurrentCredential;
        private int mUserId;
        private boolean mRequestGatekeeperPassword;
        private boolean mRequestWriteRepairModePassword;

        private GlifLayout mLayout;
        private KnockCodePadView mPad;
        private TextView mMessage;
        private Button mContinueButton;

        /**
         * The first entry, held until an identical second entry confirms it. This is the only copy
         * of the code held in memory here; the confirmed value is converted straight to a
         * {@link LockscreenCredential} and this field is dropped.
         */
        @Nullable private String mFirstSequence;

        private Stage mUiStage = Stage.Introduction;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mLockPatternUtils = new LockPatternUtils(getActivity());

            if (!(getActivity() instanceof ChooseLockKnockCode)) {
                throw new SecurityException("Fragment contained in wrong activity");
            }

            final Intent intent = getActivity().getIntent();
            // Only honoured if the id belongs to the current profile.
            mUserId = Utils.getUserIdFromBundle(getActivity(), intent.getExtras());
            mRequestGatekeeperPassword = intent.getBooleanExtra(
                    ChooseLockSettingsHelper.EXTRA_KEY_REQUEST_GK_PW_HANDLE, false);
            mRequestWriteRepairModePassword = intent.getBooleanExtra(
                    ChooseLockSettingsHelper.EXTRA_KEY_REQUEST_WRITE_REPAIR_MODE_PW, false);
            mCurrentCredential = intent.getParcelableExtra(
                    ChooseLockSettingsHelper.EXTRA_KEY_PASSWORD);

            if (savedInstanceState != null) {
                mFirstSequence = savedInstanceState.getString(KEY_FIRST_SEQUENCE);
                mUiStage = Stage.valueOf(savedInstanceState.getString(KEY_UI_STAGE,
                        Stage.Introduction.name()));
            }

            // A save that was already running when the activity was recreated lives on in a
            // retained worker fragment. Take its callbacks back over, or this screen would sit
            // there forever with no way to know the write finished.
            mSaveAndFinishWorker = (SaveAndFinishWorker) getFragmentManager()
                    .findFragmentByTag(FRAGMENT_TAG_SAVE_AND_FINISH);
            if (mSaveAndFinishWorker != null) {
                mSaveAndFinishWorker.setListener(this);
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                Bundle savedInstanceState) {
            return inflater.inflate(R.layout.choose_lock_knock_code, container, false);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            mLayout = (GlifLayout) view;
            mPad = view.findViewById(R.id.knock_code_pad);
            mMessage = view.findViewById(R.id.knock_code_message);
            mContinueButton = view.findViewById(R.id.knock_code_continue);
            mContinueButton.setOnClickListener(v -> handleContinue());

            updateStage();
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putString(KEY_FIRST_SEQUENCE, mFirstSequence);
            outState.putString(KEY_UI_STAGE, mUiStage.name());
        }

        /** Single writer for the header, button label and hint, so they can never disagree. */
        private void updateStage() {
            switch (mUiStage) {
                case Introduction:
                    mLayout.setHeaderText(getString(R.string.knock_code_create_header));
                    mContinueButton.setText(R.string.knock_code_continue);
                    mMessage.setText(R.string.knock_code_create_hint);
                    break;

                case NeedToConfirm:
                    mLayout.setHeaderText(getString(R.string.knock_code_confirm_header));
                    mContinueButton.setText(R.string.knock_code_confirm_button);
                    mMessage.setText(R.string.knock_code_confirm_hint);
                    break;

                case ConfirmWrong:
                    mLayout.setHeaderText(getString(R.string.knock_code_confirm_header));
                    mContinueButton.setText(R.string.knock_code_confirm_button);
                    mMessage.setText(R.string.knock_code_mismatch);
                    break;
            }
            mPad.clearSequence();
        }

        private void handleContinue() {
            final String sequence = mPad.getSequence();

            if (sequence.length() < LockPatternUtils.KNOCK_CODE_LENGTH_MIN) {
                mMessage.setText(getString(R.string.knock_code_too_short,
                        LockPatternUtils.KNOCK_CODE_LENGTH_MIN));
                return;
            }

            switch (mUiStage) {
                case Introduction:
                    mFirstSequence = sequence;
                    mUiStage = Stage.NeedToConfirm;
                    updateStage();
                    break;

                case NeedToConfirm:
                case ConfirmWrong:
                    if (TextUtils.equals(mFirstSequence, sequence)) {
                        mPad.setEnabled(false);
                        mContinueButton.setEnabled(false);
                        saveAndFinish(sequence);
                    } else {
                        // The first sequence is deliberately kept rather than restarting
                        // enrollment: the user is being asked to repeat a code they have already
                        // given once, and throwing it away here would leave them re-deciding what
                        // the code is at the exact moment they are least sure.
                        mUiStage = Stage.ConfirmWrong;
                        updateStage();
                    }
                    break;
            }
        }

        private void saveAndFinish(String sequence) {
            final LockscreenCredential chosen = LockscreenCredential.createPin(sequence);

            mSaveAndFinishWorker = new SaveAndFinishWorker();
            mSaveAndFinishWorker.setListener(this)
                    .setRequestGatekeeperPasswordHandle(mRequestGatekeeperPassword)
                    .setRequestWriteRepairModePassword(mRequestWriteRepairModePassword);

            getFragmentManager().beginTransaction()
                    .add(mSaveAndFinishWorker, FRAGMENT_TAG_SAVE_AND_FINISH).commit();
            getFragmentManager().executePendingTransactions();

            final Intent intent = getActivity().getIntent();
            if (intent.hasExtra(EXTRA_KEY_UNIFICATION_PROFILE_ID)) {
                try (LockscreenCredential profileCredential = intent.getParcelableExtra(
                        EXTRA_KEY_UNIFICATION_PROFILE_CREDENTIAL)) {
                    mSaveAndFinishWorker.setProfileToUnify(
                            intent.getIntExtra(EXTRA_KEY_UNIFICATION_PROFILE_ID,
                                    UserHandle.USER_NULL),
                            profileCredential);
                }
            }

            // The worker writes the pattern size back on every save. A Knock Code has no pattern
            // size of its own, so the current value is read and passed straight through, making
            // that write a no-op instead of resetting an unrelated setting to its default.
            mSaveAndFinishWorker.start(mLockPatternUtils, chosen, mCurrentCredential, mUserId,
                    mLockPatternUtils.getLockPatternSize(mUserId));
        }

        @Override
        public void onChosenLockSaveFinished(boolean wasSecureBefore, Intent resultData) {
            // The credential write is what invalidates any previous Knock Code, so the markers are
            // only set once it has actually gone through. Setting them over a failed save would
            // leave the lock screen waiting for taps that no stored credential can match.
            //
            // Length first, then the flag, because these are two separate writes and either can
            // fail. If only the length lands, the feature stays off and the user falls back to the
            // PIN keypad, where the same digits still work. The other order would leave the tap
            // grid showing while the lock screen had no idea how many taps to wait for.
            if (mSaveAndFinishWorker != null && mSaveAndFinishWorker.wasSaveSuccessful()
                    && mFirstSequence != null) {
                mLockPatternUtils.setKnockCodeLength(mFirstSequence.length(), mUserId);
                mLockPatternUtils.setKnockCodeEnabled(true, mUserId);
            }

            getActivity().setResult(RESULT_FINISHED, resultData);

            if (mCurrentCredential != null) {
                mCurrentCredential.zeroize();
            }
            mFirstSequence = null;

            if (!wasSecureBefore) {
                final Intent intent = getRedactionInterstitialIntent(getActivity());
                if (intent != null) {
                    startActivity(intent);
                }
            }

            getActivity().finish();
        }

        protected Intent getRedactionInterstitialIntent(Context context) {
            return RedactionInterstitial.createStartIntent(context, mUserId);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mCurrentCredential != null) {
                mCurrentCredential.zeroize();
            }
        }

        @Override
        public int getMetricsCategory() {
            // A Knock Code is stored and verified as a PIN, so it is counted as one; there is no
            // separate metric to attribute it to.
            return SettingsEnums.CHOOSE_LOCK_PASSWORD;
        }
    }
}
