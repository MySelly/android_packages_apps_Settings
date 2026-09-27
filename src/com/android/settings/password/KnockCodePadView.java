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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.widget.LockPatternUtils;
import com.android.settings.R;

/**
 * The 2x2 tap surface used to enter a Knock Code during enrollment.
 *
 * <p>A tap is assigned a digit from the quadrant it lands in: top-left is 1, top-right is 2,
 * bottom-left is 3, bottom-right is 4. Concatenating those digits gives a plain numeric string,
 * which is what the rest of the platform stores and verifies - the Knock Code is a PIN whose
 * digits happen to have been produced by tapping rather than by a keypad. See
 * {@link ChooseLockKnockCode} for where that conversion is handed over.
 *
 * <p>The pad deliberately shows only <em>how many</em> taps have been collected, never
 * <em>where</em> they landed, so that the same interaction can be used on the lock screen without
 * leaking the code to anyone watching. A short ripple marks each accepted tap, which is the only
 * feedback that the tap registered.
 */
public class KnockCodePadView extends View {

    /** Digit appended for a tap in each quadrant; index is the quadrant, 0-based. */
    private static final char[] QUADRANT_DIGITS = {'1', '2', '3', '4'};

    /** How long a tap ripple takes to fade out. */
    private static final long RIPPLE_DURATION_MS = 220;

    private final Paint mQuadrantPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotFilledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotEmptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRipplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final StringBuilder mSequence = new StringBuilder(LockPatternUtils.KNOCK_CODE_LENGTH_MAX);

    /** The square the user actually taps in, in view coordinates. */
    private final RectF mSquare = new RectF();

    private final float mQuadrantInset;
    private final float mQuadrantStroke;
    private final float mDotRadius;
    private final float mDotSpacing;
    private final float mDotStroke;
    private final float mRippleRadius;
    private final float mMaxSize;

    private final int mRippleColor;

    /** Baseline of the progress dot row, below {@link #mSquare}. */
    private float mDotsY;

    /** False until a layout pass has produced a square large enough to draw in. */
    private boolean mHasGeometry;

    private float mRippleX;
    private float mRippleY;
    private long mRippleStart;
    private boolean mRippleActive;

    public KnockCodePadView(Context context) {
        this(context, null);
    }

    public KnockCodePadView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KnockCodePadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mQuadrantInset = getResources().getDimension(R.dimen.knock_code_quadrant_inset);
        mQuadrantStroke = getResources().getDimension(R.dimen.knock_code_quadrant_stroke);
        mDotRadius = getResources().getDimension(R.dimen.knock_code_dot_radius);
        mDotSpacing = getResources().getDimension(R.dimen.knock_code_dot_spacing);
        mDotStroke = getResources().getDimension(R.dimen.knock_code_dot_stroke);
        mRippleRadius = getResources().getDimension(R.dimen.knock_code_ripple_radius);
        mMaxSize = getResources().getDimension(R.dimen.knock_code_pad_max_size);

        mQuadrantPaint.setStyle(Paint.Style.STROKE);
        mQuadrantPaint.setStrokeWidth(mQuadrantStroke);
        mQuadrantPaint.setColor(getContext().getColor(R.color.knock_code_quadrant));

        mDotFilledPaint.setStyle(Paint.Style.FILL);
        mDotFilledPaint.setColor(getContext().getColor(R.color.knock_code_dot_filled));

        mDotEmptyPaint.setStyle(Paint.Style.STROKE);
        mDotEmptyPaint.setStrokeWidth(mDotStroke);
        mDotEmptyPaint.setColor(getContext().getColor(R.color.knock_code_dot_empty));

        mRippleColor = getContext().getColor(R.color.knock_code_ripple);
        mRipplePaint.setStyle(Paint.Style.FILL);
        mRipplePaint.setColor(mRippleColor);

        setClickable(true);
    }

    /** The encoded tap sequence entered so far, one digit per tap. */
    public String getSequence() {
        return mSequence.toString();
    }

    /** Drops the collected taps so the pad can be used for a fresh sequence. */
    public void clearSequence() {
        mSequence.setLength(0);
        mRippleActive = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeGeometry(w, h);
    }

    /**
     * Works out where the tappable square sits. Both drawing and hit testing go through this, so a
     * tap can never land in a quadrant other than the one the user sees under their finger.
     */
    private void computeGeometry(int w, int h) {
        // Vertical space the progress dots need, reserved up front so the square never overlaps
        // them however tall the view ends up being.
        final float dotsBand = mDotRadius * 2f + mDotSpacing;

        final float side = Math.min(
                Math.min(w - 2f * mQuadrantInset, h - 2f * mQuadrantInset - dotsBand),
                mMaxSize);

        mHasGeometry = side > 0f;
        if (!mHasGeometry) {
            return;
        }

        final float contentHeight = side + dotsBand;
        final float top = (h - contentHeight) / 2f;
        final float left = (w - side) / 2f;
        mSquare.set(left, top, left + side, top + side);
        mDotsY = mSquare.bottom + dotsBand / 2f;
    }

    /**
     * Quadrant index for a point, 0-based clockwise from the top-left. Quadrants are relative to
     * what is on screen: the pad is not rotated for landscape, because the user taps what they see
     * and the encoded digits must not depend on device orientation.
     */
    private int quadrantFor(float x, float y) {
        final boolean right = x >= mSquare.centerX();
        final boolean bottom = y >= mSquare.centerY();
        return (bottom ? 2 : 0) + (right ? 1 : 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || !mHasGeometry) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The enrollment screen scrolls; keep the gesture here so a tap with a little
                // lateral movement is not turned into a scroll.
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                onTap(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void onTap(float x, float y) {
        // Taps outside the drawn square are ignored rather than clamped into a quadrant: the user
        // aimed at nothing, so guessing which quadrant they meant would put a digit in the code
        // that they never intended and cannot see.
        if (!mSquare.contains(x, y)) {
            return;
        }
        if (mSequence.length() >= LockPatternUtils.KNOCK_CODE_LENGTH_MAX) {
            return;
        }

        mSequence.append(QUADRANT_DIGITS[quadrantFor(x, y)]);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        startRipple(x, y);
        invalidate();
    }

    private void startRipple(float x, float y) {
        mRippleX = x;
        mRippleY = y;
        mRippleStart = SystemClock.uptimeMillis();
        mRippleActive = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mHasGeometry) {
            return;
        }

        // A softly rounded square, not a circle: rx = ry = half the side would round the corners
        // all the way into one, and the quadrant cross inside it would read as a pie chart rather
        // than as four corners to tap.
        final float corner = mQuadrantInset / 2f;
        canvas.drawRoundRect(mSquare, corner, corner, mQuadrantPaint);
        canvas.drawLine(mSquare.centerX(), mSquare.top,
                mSquare.centerX(), mSquare.bottom, mQuadrantPaint);
        canvas.drawLine(mSquare.left, mSquare.centerY(),
                mSquare.right, mSquare.centerY(), mQuadrantPaint);

        drawProgressDots(canvas);
        drawRipple(canvas);
    }

    /**
     * One dot per accepted tap, but never fewer than the minimum code length, so the user can see
     * how much further they have to go before the code is long enough to accept.
     */
    private void drawProgressDots(Canvas canvas) {
        final float centerX = mSquare.centerX();
        final int shown = Math.max(LockPatternUtils.KNOCK_CODE_LENGTH_MIN, mSequence.length());
        final float rowWidth = (shown - 1) * mDotSpacing;
        float x = centerX - rowWidth / 2f;

        for (int i = 0; i < shown; i++) {
            canvas.drawCircle(x, mDotsY, mDotRadius,
                    i < mSequence.length() ? mDotFilledPaint : mDotEmptyPaint);
            x += mDotSpacing;
        }
    }

    private void drawRipple(Canvas canvas) {
        if (!mRippleActive) {
            return;
        }

        final float progress =
                (SystemClock.uptimeMillis() - mRippleStart) / (float) RIPPLE_DURATION_MS;
        if (progress >= 1f) {
            mRippleActive = false;
            return;
        }

        mRipplePaint.setColor(mRippleColor);
        mRipplePaint.setAlpha((int) (Color.alpha(mRippleColor) * (1f - progress)));
        canvas.drawCircle(mRippleX, mRippleY, mRippleRadius, mRipplePaint);
        postInvalidateOnAnimation();
    }
}
