package com.simplemobiletools.keyboard.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.Paint.Align
import android.graphics.drawable.Drawable
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Message
import android.util.AttributeSet
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.PopupWindow
import android.widget.TextView
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.keyboard.R
import com.simplemobiletools.keyboard.extensions.config
import com.simplemobiletools.keyboard.helpers.*
import kotlinx.android.synthetic.main.keyboard_popup_keyboard.view.*
import java.util.*

@SuppressLint("UseCompatLoadingForDrawables")
class MyKeyboardView @JvmOverloads constructor(context: Context, attrs: AttributeSet?, defStyleRes: Int = 0) :
    View(context, attrs, defStyleRes) {

    interface OnKeyboardActionListener {
        /**
         * Called when the user presses a key. This is sent before the [.onKey] is called. For keys that repeat, this is only called once.
         * @param primaryCode the unicode of the key being pressed. If the touch is not on a valid key, the value will be zero.
         */
        fun onPress(primaryCode: Int)

        /**
         * Send a key press to the listener.
         * @param primaryCode this is the key that was pressed
         * @param keyCodes the codes for all the possible alternative keys with the primary code being the first. If the primary key code is a single character
         * such as an alphabet or number or symbol, the alternatives will include other characters that may be on the same key or adjacent keys. These codes
         * are useful to correct for accidental presses of a key adjacent to the intended key.
         */
        fun onKey(primaryCode: Int, keyCodes: IntArray?)

        /**
         * Called when the finger has been lifted after pressing a key
         */
        fun onActionUp()

        /**
         * Called when the user long presses Space and moves to the left
         */
        fun moveCursorLeft()

        /**
         * Called when the user long presses Space and moves to the right
         */
        fun moveCursorRight()
    }

    private var mKeyboard: MyKeyboard? = null
    private var mCurrentKeyIndex: Int = NOT_A_KEY

    private var mLabelTextSize = 0
    private var mKeyTextSize = 0

    private var mTextColor = 0
    private var mBackgroundColor = 0
    private var mPrimaryColor = 0

    private var mPreviewText: TextView? = null
    private val mPreviewPopup: PopupWindow
    private var mPreviewTextSizeLarge = 0
    private var mPreviewHeight = 0

    private val mCoordinates = IntArray(2)
    private val mPopupKeyboard: PopupWindow
    private var mMiniKeyboardContainer: View? = null
    private var mMiniKeyboard: MyKeyboardView? = null
    private var mMiniKeyboardOnScreen = false
    private var mPopupParent: View
    private var mMiniKeyboardOffsetX = 0
    private var mMiniKeyboardOffsetY = 0
    private val mMiniKeyboardCache: MutableMap<MyKeyboard.Key, View?>
    private var mKeys = ArrayList<MyKeyboard.Key>()
    private var mMiniKeyboardSelectedKeyIndex = -1

    var mOnKeyboardActionListener: OnKeyboardActionListener? = null
    private var mVerticalCorrection = 0
    private var mProximityThreshold = 0
    private var mPopupPreviewX = 0
    private var mPopupPreviewY = 0
    private var mLastX = 0
    private var mLastY = 0

    private val mPaint: Paint
    private var mDownTime: Long = 0
    private var mLastMoveTime: Long = 0
    private var mLastKey = 0
    private var mLastCodeX = 0
    private var mLastCodeY = 0
    private var mCurrentKey: Int = NOT_A_KEY
    private var mLastKeyTime = 0L
    private var mCurrentKeyTime = 0L
    private val mKeyIndices = IntArray(12)
    private var mPopupX = 0
    private var mPopupY = 0
    private var mRepeatKeyIndex = NOT_A_KEY
    private var mPopupLayout = 0
    private var mAbortKey = false
    private var mIsLongPressingSpace = false
    private var mLastSpaceMoveX = 0
    private var mPopupMaxMoveDistance = 0f
    private var mTopSmallNumberSize = 0f
    private var mTopSmallNumberMarginWidth = 0f
    private var mTopSmallNumberMarginHeight = 0f
    private val mSpaceMoveThreshold: Int

    // Variables for dealing with multiple pointers
    private var mOldPointerCount = 1
    private var mOldPointerX = 0f
    private var mOldPointerY = 0f

    private var mKeyBackground: Drawable? = null
    private val mDistances = IntArray(MAX_NEARBY_KEYS)

    // For multi-tap
    private var mLastSentIndex = 0
    private var mTapCount = 0
    private var mLastTapTime: Long = 0
    private var mInMultiTap = false
    private val mPreviewLabel = StringBuilder(1)

    /** Whether the keyboard bitmap needs to be redrawn before it's blitted.  */
    private var mDrawPending = false

    /** The dirty region in the keyboard bitmap  */
    private val mDirtyRect = Rect()

    /** The keyboard bitmap for faster updates  */
    private var mBuffer: Bitmap? = null

    /** Notes if the keyboard just changed, so that we could possibly reallocate the mBuffer.  */
    private var mKeyboardChanged = false

    /** The canvas for the above mutable keyboard bitmap  */
    private var mCanvas: Canvas? = null

    /** The accessibility manager for accessibility support  */
    private val mAccessibilityManager: AccessibilityManager

    private var mHandler: Handler? = null

    companion object {
        private const val NOT_A_KEY = -1
        private val KEY_DELETE = intArrayOf(MyKeyboard.KEYCODE_DELETE)
        private val LONG_PRESSABLE_STATE_SET = intArrayOf(R.attr.state_long_pressable)
        private const val MSG_SHOW_PREVIEW = 1
        private const val MSG_REMOVE_PREVIEW = 2
        private const val MSG_REPEAT = 3
        private const val MSG_LONGPRESS = 4
        private const val DELAY_BEFORE_PREVIEW = 0
        private const val DELAY_AFTER_PREVIEW = 70
        private const val DEBOUNCE_TIME = 70
        private const val REPEAT_INTERVAL = 50 // ~20 keys per second
        private const val REPEAT_START_DELAY = 400
        private val LONGPRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout()
        private const val MAX_NEARBY_KEYS = 12
        private const val MULTITAP_INTERVAL = 800 // milliseconds
    }

    init {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.MyKeyboardView, 0, defStyleRes)
        val inflate = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val keyTextSize = 0
        val indexCnt = attributes.indexCount

        try {
            for (i in 0 until indexCnt) {
                val attr = attributes.getIndex(i)
                when (attr) {
                    R.styleable.MyKeyboardView_keyTextSize -> mKeyTextSize = attributes.getDimensionPixelSize(attr, 18)
                }
            }
        } finally {
            attributes.recycle()
        }

        mPopupLayout = R.layout.keyboard_popup_keyboard
        mKeyBackground = resources.getDrawable(R.drawable.keyboard_key_selector, context.theme)
        mVerticalCorrection = resources.getDimension(R.dimen.vertical_correction).toInt()
        mLabelTextSize = resources.getDimension(R.dimen.label_text_size).toInt()
        mPreviewHeight = resources.getDimension(R.dimen.key_height).toInt()
        mSpaceMoveThreshold = resources.getDimension(R.dimen.medium_margin).toInt()
        mTextColor = context.config.textColor
        mBackgroundColor = context.config.backgroundColor
        mPrimaryColor = context.getAdjustedPrimaryColor()

        mPreviewPopup = PopupWindow(context)
        mPreviewText = inflate.inflate(resources.getLayout(R.layout.keyboard_key_preview), null) as TextView
        mPreviewTextSizeLarge = context.resources.getDimension(R.dimen.preview_text_size).toInt()
        mPreviewPopup.contentView = mPreviewText
        mPreviewPopup.setBackgroundDrawable(null)

        mPreviewPopup.isTouchable = false
        mPopupKeyboard = PopupWindow(context)
        mPopupKeyboard.setBackgroundDrawable(null)
        mPopupParent = this
        mPaint = Paint()
        mPaint.isAntiAlias = true
        mPaint.textSize = keyTextSize.toFloat()
        mPaint.textAlign = Align.CENTER
        mPaint.alpha = 255
        mMiniKeyboardCache = HashMap()
        mAccessibilityManager = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        mPopupMaxMoveDistance = resources.getDimension(R.dimen.popup_max_move_distance)
        mTopSmallNumberSize = resources.getDimension(R.dimen.small_text_size)
        mTopSmallNumberMarginWidth = resources.getDimension(R.dimen.top_small_number_margin_width)
        mTopSmallNumberMarginHeight = resources.getDimension(R.dimen.top_small_number_margin_height)
        resetMultiTap()
    }

    @SuppressLint("HandlerLeak")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mHandler == null) {
            mHandler = object : Handler() {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_SHOW_PREVIEW -> showKey(msg.arg1)
                        MSG_REMOVE_PREVIEW -> mPreviewText!!.visibility = INVISIBLE
                        MSG_REPEAT -> if (repeatKey(false)) {
                            val repeat = Message.obtain(this, MSG_REPEAT)
                            sendMessageDelayed(repeat, REPEAT_INTERVAL.toLong())
                        }
                        MSG_LONGPRESS -> openPopupIfRequired(msg.obj as MotionEvent)
                    }
                }
            }
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            mTextColor = context.config.textColor
            mBackgroundColor = context.config.backgroundColor
            mPrimaryColor = context.getAdjustedPrimaryColor()

            var newBgColor = mBackgroundColor
            if (changedView == mini_keyboard_view) {
                newBgColor = newBgColor.darkenColor(4)
            }

            background.applyColorFilter(newBgColor.darkenColor(2))
        }
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see MyKeyboard
     *
     * @see .getKeyboard
     * @param keyboard the keyboard to display in this view
     */
    fun setKeyboard(keyboard: MyKeyboard) {
        if (mKeyboard != null) {
            showPreview(NOT_A_KEY)
        }

        removeMessages()
        mKeyboard = keyboard
        val keys = mKeyboard!!.mKeys
        mKeys = keys!!.toMutableList() as ArrayList<MyKeyboard.Key>
        requestLayout()
        mKeyboardChanged = true
        invalidateAllKeys()
        computeProximityThreshold(keyboard)
        mMiniKeyboardCache.clear()
        // Not really necessary to do every time, but will free up views
        // Switching to a different keyboard should abort any pending keys so that the key up
        // doesn't get delivered to the old or new keyboard
        mAbortKey = true // Until the next ACTION_DOWN
    }

    /**
     * Sets the state of the shift key of the keyboard, if any.
     * @param shifted whether or not to enable the state of the shift key
     * @return true if the shift key state changed, false if there was no change
     * @see KeyboardView.isShifted
     */
    private fun setShifted(shiftState: Int) {
        if (mKeyboard?.setShifted(shiftState) == true) {
            invalidateAllKeys()
        }
    }

    /**
     * Returns the state of the shift key of the keyboard, if any.
     * @return true if the shift is in a pressed state, false otherwise. If there is
     * no shift key on the keyboard or there is no keyboard attached, it returns false.
     * @see KeyboardView.setShifted
     */
    private fun isShifted(): Boolean {
        return mKeyboard?.mShiftState ?: SHIFT_OFF > SHIFT_OFF
    }

    private fun setPopupOffset(x: Int, y: Int) {
        mMiniKeyboardOffsetX = x
        mMiniKeyboardOffsetY = y
        if (mPreviewPopup.isShowing) {
            mPreviewPopup.dismiss()
        }
    }

    private fun adjustCase(label: CharSequence): CharSequence? {
        var newLabel: CharSequence? = label
        if (newLabel != null && newLabel.isNotEmpty() && mKeyboard!!.mShiftState > SHIFT_OFF && newLabel.length < 3 && Character.isLowerCase(newLabel[0])) {
            newLabel = newLabel.toString().toUpperCase()
        }
        return newLabel
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (mKeyboard == null) {
            setMeasuredDimension(0, 0)
        } else {
            var width: Int = mKeyboard!!.mMinWidth
            if (MeasureSpec.getSize(widthMeasureSpec) < width + 10) {
                width = MeasureSpec.getSize(widthMeasureSpec)
            }
            setMeasuredDimension(width, mKeyboard!!.mHeight)
        }
    }

    /**
     * Compute the average distance between adjacent keys (horizontally and vertically)
     * and square it to get the proximity threshold. We use a square here and in computing
     * the touch distance from a key's center to avoid taking a square root.
     * @param keyboard
     */
    private fun computeProximityThreshold(keyboard: MyKeyboard?) {
        if (keyboard == null) {
            return
        }

        val keys = mKeys
        val length = keys.size
        var dimensionSum = 0
        for (i in 0 until length) {
            val key = keys[i]
            dimensionSum += Math.min(key.width, key.height) + key.gap
        }

        if (dimensionSum < 0 || length == 0) {
            return
        }

        mProximityThreshold = (dimensionSum * 1.4f / length).toInt()
        mProximityThreshold *= mProximityThreshold // Square it
    }

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mDrawPending || mBuffer == null || mKeyboardChanged) {
            onBufferDraw()
        }
        canvas.drawBitmap(mBuffer!!, 0f, 0f, null)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun onBufferDraw() {
        if (mBuffer == null || mKeyboardChanged) {
            if (mBuffer == null || mKeyboardChanged && (mBuffer!!.width != width || mBuffer!!.height != height)) {
                // Make sure our bitmap is at least 1x1
                val width = Math.max(1, width)
                val height = Math.max(1, height)
                mBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                mCanvas = Canvas(mBuffer!!)
            }
            invalidateAllKeys()
            mKeyboardChanged = false
        }

        if (mKeyboard == null) {
            return
        }

        mCanvas!!.save()
        val canvas = mCanvas
        canvas!!.clipRect(mDirtyRect)
        val paint = mPaint
        val keys = mKeys
        paint.color = mTextColor
        val smallLetterPaint = Paint()
        smallLetterPaint.set(paint)
        smallLetterPaint.apply {
            color = paint.color.adjustAlpha(0.8f)
            textSize = mTopSmallNumberSize
            typeface = Typeface.DEFAULT
        }

        canvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR)
        val keyCount = keys.size
        for (i in 0 until keyCount) {
            val key = keys[i]
            val code = key.codes.firstOrNull() ?: -100
            var keyBackground = mKeyBackground
            if (code == MyKeyboard.KEYCODE_SPACE) {
                keyBackground = resources.getDrawable(R.drawable.keyboard_space_background, context.theme)
            } else if (code == MyKeyboard.KEYCODE_ENTER) {
                keyBackground = resources.getDrawable(R.drawable.keyboard_enter_background, context.theme)
            }

            // Switch the character to uppercase if shift is pressed
            val label = adjustCase(key.label)?.toString()
            val bounds = keyBackground!!.bounds
            if (key.width != bounds.right || key.height != bounds.bottom) {
                keyBackground.setBounds(0, 0, key.width, key.height)
            }

            keyBackground.state = when {
                key.pressed -> intArrayOf(android.R.attr.state_pressed)
                key.focused -> intArrayOf(android.R.attr.state_focused)
                else -> intArrayOf()
            }

            if (key.focused || code == MyKeyboard.KEYCODE_ENTER) {
                keyBackground.applyColorFilter(mPrimaryColor)
            }

            canvas.translate(key.x.toFloat(), key.y.toFloat())
            keyBackground.draw(canvas)
            if (label?.isNotEmpty() == true) {
                // For characters, use large font. For labels like "Done", use small font.
                if (label.length > 1 && key.codes.size < 2) {
                    paint.textSize = mLabelTextSize.toFloat()
                    paint.typeface = Typeface.DEFAULT_BOLD
                } else {
                    paint.textSize = mKeyTextSize.toFloat()
                    paint.typeface = Typeface.DEFAULT
                }

                paint.color = if (key.focused) {
                    mPrimaryColor.getContrastColor()
                } else {
                    mTextColor
                }

                canvas.drawText(
                    label, (key.width / 2).toFloat(), key.height / 2 + (paint.textSize - paint.descent()) / 2, paint
                )

                if (key.topSmallNumber.isNotEmpty()) {
                    canvas.drawText(key.topSmallNumber, key.width - mTopSmallNumberMarginWidth, mTopSmallNumberMarginHeight, smallLetterPaint)
                }

                // Turn off drop shadow
                paint.setShadowLayer(0f, 0f, 0f, 0)
            } else if (key.icon != null && mKeyboard != null) {
                if (key.codes.size == 1 && key.codes.contains(-1)) {
                    val drawableId = when (mKeyboard!!.mShiftState) {
                        SHIFT_OFF -> R.drawable.ic_caps_outline_vector
                        SHIFT_ON_ONE_CHAR -> R.drawable.ic_caps_vector
                        else -> R.drawable.ic_caps_underlined_vector
                    }
                    key.icon = resources.getDrawable(drawableId)
                }

                if (code == MyKeyboard.KEYCODE_ENTER) {
                    key.icon!!.applyColorFilter(mPrimaryColor.getContrastColor())
                } else if (code == MyKeyboard.KEYCODE_DELETE || code == MyKeyboard.KEYCODE_SHIFT) {
                    key.icon!!.applyColorFilter(mTextColor)
                }

                val drawableX = (key.width - key.icon!!.intrinsicWidth) / 2
                val drawableY = (key.height - key.icon!!.intrinsicHeight) / 2
                canvas.translate(drawableX.toFloat(), drawableY.toFloat())
                key.icon!!.setBounds(0, 0, key.icon!!.intrinsicWidth, key.icon!!.intrinsicHeight)
                key.icon!!.draw(canvas)
                canvas.translate(-drawableX.toFloat(), -drawableY.toFloat())
            }
            canvas.translate(-key.x.toFloat(), -key.y.toFloat())
        }

        // Overlay a dark rectangle to dim the keyboard
        if (mMiniKeyboardOnScreen) {
            paint.color = Color.BLACK.adjustAlpha(0.3f)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        mCanvas!!.restore()
        mDrawPending = false
        mDirtyRect.setEmpty()
    }

    private fun getKeyIndices(x: Int, y: Int, allKeys: IntArray?): Int {
        val keys = mKeys
        var primaryIndex = NOT_A_KEY
        var closestKey = NOT_A_KEY
        var closestKeyDist = mProximityThreshold + 1
        Arrays.fill(mDistances, Int.MAX_VALUE)
        val nearestKeyIndices = mKeyboard!!.getNearestKeys(x, y)
        val keyCount = nearestKeyIndices.size

        for (i in 0 until keyCount) {
            val key = keys[nearestKeyIndices[i]]
            val dist = 0
            val isInside = key.isInside(x, y)
            if (isInside) {
                primaryIndex = nearestKeyIndices[i]
            }

            if (isInside && key.codes[0] > MyKeyboard.KEYCODE_SPACE) {
                // Find insertion point
                val nCodes = key.codes.size
                if (dist < closestKeyDist) {
                    closestKeyDist = dist
                    closestKey = nearestKeyIndices[i]
                }

                if (allKeys == null) {
                    continue
                }

                for (j in mDistances.indices) {
                    if (mDistances[j] > dist) {
                        // Make space for nCodes codes
                        System.arraycopy(
                            mDistances, j, mDistances, j + nCodes,
                            mDistances.size - j - nCodes
                        )
                        System.arraycopy(
                            allKeys, j, allKeys, j + nCodes,
                            allKeys.size - j - nCodes
                        )

                        for (c in 0 until nCodes) {
                            allKeys[j + c] = key.codes[c]
                            mDistances[j + c] = dist
                        }
                        break
                    }
                }
            }
        }

        if (primaryIndex == NOT_A_KEY) {
            primaryIndex = closestKey
        }

        return primaryIndex
    }

    private fun detectAndSendKey(index: Int, x: Int, y: Int, eventTime: Long) {
        if (index != NOT_A_KEY && index < mKeys.size) {
            val key = mKeys[index]
            var code = key.codes[0]
            val codes = IntArray(MAX_NEARBY_KEYS)
            Arrays.fill(codes, NOT_A_KEY)
            getKeyIndices(x, y, codes)
            // Multi-tap
            if (mInMultiTap) {
                if (mTapCount != -1) {
                    mOnKeyboardActionListener!!.onKey(MyKeyboard.KEYCODE_DELETE, KEY_DELETE)
                } else {
                    mTapCount = 0
                }
                code = key.codes[mTapCount]
            }
            mOnKeyboardActionListener!!.onKey(code, codes)
            mLastSentIndex = index
            mLastTapTime = eventTime
        }
    }

    /**
     * Handle multi-tap keys by producing the key label for the current multi-tap state.
     */
    private fun getPreviewText(key: MyKeyboard.Key): CharSequence? {
        return if (mInMultiTap) {
            // Multi-tap
            mPreviewLabel.setLength(0)
            val codeTapCount = if (mTapCount < 0) {
                0
            } else {
                mTapCount
            }

            mPreviewLabel.append(key.codes[codeTapCount].toChar())
            adjustCase(mPreviewLabel)
        } else {
            adjustCase(key.label)
        }
    }

    private fun showPreview(keyIndex: Int) {
        val oldKeyIndex = mCurrentKeyIndex
        val previewPopup = mPreviewPopup
        mCurrentKeyIndex = keyIndex
        // Release the old key and press the new key
        val keys = mKeys
        if (oldKeyIndex != mCurrentKeyIndex) {
            if (oldKeyIndex != NOT_A_KEY && keys.size > oldKeyIndex) {
                val oldKey = keys[oldKeyIndex]
                oldKey.onReleased()
                invalidateKey(oldKeyIndex)
                val keyCode = oldKey.codes[0]
                sendAccessibilityEventForUnicodeCharacter(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT, keyCode)
                sendAccessibilityEventForUnicodeCharacter(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, keyCode)
            }

            if (mCurrentKeyIndex != NOT_A_KEY && keys.size > mCurrentKeyIndex) {
                val newKey = keys[mCurrentKeyIndex]

                val code = newKey.codes.firstOrNull() ?: -100
                if (code == MyKeyboard.KEYCODE_SHIFT || code == MyKeyboard.KEYCODE_MODE_CHANGE || code == MyKeyboard.KEYCODE_DELETE ||
                    code == MyKeyboard.KEYCODE_ENTER || code == MyKeyboard.KEYCODE_SPACE
                ) {
                    newKey.onPressed()
                }

                invalidateKey(mCurrentKeyIndex)
                val keyCode = newKey.codes[0]
                sendAccessibilityEventForUnicodeCharacter(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, keyCode)
                sendAccessibilityEventForUnicodeCharacter(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, keyCode)
            }
        }

        // If key changed and preview is on ...
        if (oldKeyIndex != mCurrentKeyIndex) {
            mHandler!!.removeMessages(MSG_SHOW_PREVIEW)
            if (previewPopup.isShowing) {
                if (keyIndex == NOT_A_KEY) {
                    mHandler!!.sendMessageDelayed(
                        mHandler!!.obtainMessage(MSG_REMOVE_PREVIEW),
                        DELAY_AFTER_PREVIEW.toLong()
                    )
                }
            }

            if (keyIndex != NOT_A_KEY) {
                if (previewPopup.isShowing && mPreviewText!!.visibility == VISIBLE) {
                    // Show right away, if it's already visible and finger is moving around
                    showKey(keyIndex)
                } else {
                    mHandler!!.sendMessageDelayed(
                        mHandler!!.obtainMessage(MSG_SHOW_PREVIEW, keyIndex, 0),
                        DELAY_BEFORE_PREVIEW.toLong()
                    )
                }
            }
        }
    }

    private fun showKey(keyIndex: Int) {
        val previewPopup = mPreviewPopup
        val keys = mKeys
        if (keyIndex < 0 || keyIndex >= mKeys.size) {
            return
        }

        val key = keys[keyIndex]
        if (key.icon != null) {
            mPreviewText!!.setCompoundDrawables(null, null, null, key.icon)
        } else {
            mPreviewText!!.setCompoundDrawables(null, null, null, null)
            try {
                mPreviewText!!.text = getPreviewText(key)
            } catch (ignored: Exception) {
            }

            if (key.label.length > 1 && key.codes.size < 2) {
                mPreviewText!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, mKeyTextSize.toFloat())
                mPreviewText!!.typeface = Typeface.DEFAULT_BOLD
            } else {
                mPreviewText!!.setTextSize(TypedValue.COMPLEX_UNIT_PX, mPreviewTextSizeLarge.toFloat())
                mPreviewText!!.typeface = Typeface.DEFAULT
            }
        }

        mPreviewText!!.background.applyColorFilter(mBackgroundColor.darkenColor(4))
        mPreviewText!!.setTextColor(mTextColor)
        mPreviewText!!.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val popupWidth = Math.max(mPreviewText!!.measuredWidth, key.width)
        val popupHeight = mPreviewHeight
        val lp = mPreviewText!!.layoutParams
        lp?.width = popupWidth
        lp?.height = popupHeight

        mPopupPreviewX = key.x
        mPopupPreviewY = key.y - popupHeight

        mHandler!!.removeMessages(MSG_REMOVE_PREVIEW)
        getLocationInWindow(mCoordinates)
        mCoordinates[0] += mMiniKeyboardOffsetX // Offset may be zero
        mCoordinates[1] += mMiniKeyboardOffsetY // Offset may be zero

        // Set the preview background state
        mPreviewText!!.background.state = if (key.popupResId != 0) {
            LONG_PRESSABLE_STATE_SET
        } else {
            EMPTY_STATE_SET
        }

        mPopupPreviewX += mCoordinates[0]
        mPopupPreviewY += mCoordinates[1]

        // If the popup cannot be shown above the key, put it on the side
        getLocationOnScreen(mCoordinates)
        if (mPopupPreviewY + mCoordinates[1] < 0) {
            // If the key you're pressing is on the left side of the keyboard, show the popup on
            // the right, offset by enough to see at least one key to the left/right.
            if (key.x + key.width <= width / 2) {
                mPopupPreviewX += (key.width * 2.5).toInt()
            } else {
                mPopupPreviewX -= (key.width * 2.5).toInt()
            }
            mPopupPreviewY += popupHeight
        }

        if (key.label.isNotEmpty() && key.codes.firstOrNull() != MyKeyboard.KEYCODE_MODE_CHANGE && key.codes.firstOrNull() != MyKeyboard.KEYCODE_SHIFT) {
            if (previewPopup.isShowing) {
                previewPopup.update(mPopupPreviewX, mPopupPreviewY, popupWidth, popupHeight)
            } else {
                previewPopup.width = popupWidth
                previewPopup.height = popupHeight
                previewPopup.showAtLocation(mPopupParent, Gravity.NO_GRAVITY, mPopupPreviewX, mPopupPreviewY)
            }
            mPreviewText!!.visibility = VISIBLE
        } else {
            previewPopup.dismiss()
        }
    }

    private fun sendAccessibilityEventForUnicodeCharacter(eventType: Int, code: Int) {
        if (mAccessibilityManager.isEnabled) {
            val event = AccessibilityEvent.obtain(eventType)
            onInitializeAccessibilityEvent(event)
            val text: String = when (code) {
                MyKeyboard.KEYCODE_DELETE -> context.getString(R.string.keycode_delete)
                MyKeyboard.KEYCODE_ENTER -> context.getString(R.string.keycode_enter)
                MyKeyboard.KEYCODE_MODE_CHANGE -> context.getString(R.string.keycode_mode_change)
                MyKeyboard.KEYCODE_SHIFT -> context.getString(R.string.keycode_shift)
                else -> code.toChar().toString()
            }
            event.text.add(text)
            mAccessibilityManager.sendAccessibilityEvent(event)
        }
    }

    /**
     * Requests a redraw of the entire keyboard. Calling [.invalidate] is not sufficient
     * because the keyboard renders the keys to an off-screen buffer and an invalidate() only
     * draws the cached buffer.
     * @see .invalidateKey
     */
    fun invalidateAllKeys() {
        mDirtyRect.union(0, 0, width, height)
        mDrawPending = true
        invalidate()
    }

    /**
     * Invalidates a key so that it will be redrawn on the next repaint. Use this method if only
     * one key is changing it's content. Any changes that affect the position or size of the key
     * may not be honored.
     * @param keyIndex the index of the key in the attached [MyKeyboard].
     * @see .invalidateAllKeys
     */
    private fun invalidateKey(keyIndex: Int) {
        if (keyIndex < 0 || keyIndex >= mKeys.size) {
            return
        }

        val key = mKeys[keyIndex]
        mDirtyRect.union(
            key.x, key.y,
            key.x + key.width, key.y + key.height
        )
        onBufferDraw()
        invalidate(
            key.x, key.y,
            key.x + key.width, key.y + key.height
        )
    }

    private fun openPopupIfRequired(me: MotionEvent): Boolean {
        // Check if we have a popup layout specified first.
        if (mPopupLayout == 0) {
            return false
        }

        if (mCurrentKey < 0 || mCurrentKey >= mKeys.size) {
            return false
        }

        val popupKey = mKeys[mCurrentKey]
        val result = onLongPress(popupKey, me)
        if (result) {
            mAbortKey = true
            showPreview(NOT_A_KEY)
        }

        return result
    }

    /**
     * Called when a key is long pressed. By default this will open any popup keyboard associated
     * with this key through the attributes popupLayout and popupCharacters.
     * @param popupKey the key that was long pressed
     * @return true if the long press is handled, false otherwise. Subclasses should call the
     * method on the base class if the subclass doesn't wish to handle the call.
     */
    private fun onLongPress(popupKey: MyKeyboard.Key, me: MotionEvent): Boolean {
        val popupKeyboardId = popupKey.popupResId
        if (popupKeyboardId != 0) {
            mMiniKeyboardContainer = mMiniKeyboardCache[popupKey]
            if (mMiniKeyboardContainer == null) {
                val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                mMiniKeyboardContainer = inflater.inflate(mPopupLayout, null)
                mMiniKeyboard = mMiniKeyboardContainer!!.findViewById<View>(R.id.mini_keyboard_view) as MyKeyboardView

                mMiniKeyboard!!.mOnKeyboardActionListener = object : OnKeyboardActionListener {
                    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
                        mOnKeyboardActionListener!!.onKey(primaryCode, keyCodes)
                        dismissPopupKeyboard()
                    }

                    override fun onPress(primaryCode: Int) {
                        mOnKeyboardActionListener!!.onPress(primaryCode)
                    }

                    override fun onActionUp() {
                        mOnKeyboardActionListener!!.onActionUp()
                    }

                    override fun moveCursorLeft() {
                        mOnKeyboardActionListener!!.moveCursorLeft()
                    }

                    override fun moveCursorRight() {
                        mOnKeyboardActionListener!!.moveCursorRight()
                    }
                }

                val keyboard = if (popupKey.popupCharacters != null) {
                    MyKeyboard(context, popupKeyboardId, popupKey.popupCharacters!!)
                } else {
                    MyKeyboard(context, popupKeyboardId, 0)
                }

                mMiniKeyboard!!.setKeyboard(keyboard)
                mPopupParent = this
                mMiniKeyboardContainer!!.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
                )
                mMiniKeyboardCache[popupKey] = mMiniKeyboardContainer
            } else {
                mMiniKeyboard = mMiniKeyboardContainer!!.findViewById<View>(R.id.mini_keyboard_view) as MyKeyboardView
            }

            getLocationInWindow(mCoordinates)
            mPopupX = popupKey.x
            mPopupY = popupKey.y

            val widthToUse = mMiniKeyboardContainer!!.measuredWidth - (popupKey.popupCharacters!!.length / 2) * popupKey.width
            mPopupX = mPopupX + popupKey.width - widthToUse
            mPopupY -= mMiniKeyboardContainer!!.measuredHeight
            val x = mPopupX + mCoordinates[0]
            val y = mPopupY + mCoordinates[1]
            val xOffset = Math.max(0, x)
            mMiniKeyboard!!.setPopupOffset(xOffset, y)

            // make sure we highlight the proper key right after long pressing it, before any ACTION_MOVE event occurs
            val miniKeyboardX = if (xOffset + mMiniKeyboard!!.measuredWidth <= measuredWidth) {
                xOffset
            } else {
                measuredWidth - mMiniKeyboard!!.measuredWidth
            }

            val keysCnt = mMiniKeyboard!!.mKeys.size
            var selectedKeyIndex = Math.floor((me.x - miniKeyboardX) / popupKey.width.toDouble()).toInt()
            if (keysCnt > MAX_KEYS_PER_MINI_ROW) {
                selectedKeyIndex += MAX_KEYS_PER_MINI_ROW
            }
            selectedKeyIndex = Math.max(0, Math.min(selectedKeyIndex, keysCnt - 1))

            for (i in 0 until keysCnt) {
                mMiniKeyboard!!.mKeys[i].focused = i == selectedKeyIndex
            }

            mMiniKeyboardSelectedKeyIndex = selectedKeyIndex
            mMiniKeyboard!!.invalidateAllKeys()

            val miniShiftStatus = if (isShifted()) SHIFT_ON_PERMANENT else SHIFT_OFF
            mMiniKeyboard!!.setShifted(miniShiftStatus)
            mPopupKeyboard.contentView = mMiniKeyboardContainer
            mPopupKeyboard.width = mMiniKeyboardContainer!!.measuredWidth
            mPopupKeyboard.height = mMiniKeyboardContainer!!.measuredHeight
            mPopupKeyboard.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
            mMiniKeyboardOnScreen = true
            invalidateAllKeys()
            return true
        }
        return false
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (mAccessibilityManager.isTouchExplorationEnabled && event.pointerCount == 1) {
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> event.action = MotionEvent.ACTION_DOWN
                MotionEvent.ACTION_HOVER_MOVE -> event.action = MotionEvent.ACTION_MOVE
                MotionEvent.ACTION_HOVER_EXIT -> event.action = MotionEvent.ACTION_UP
            }
            return onTouchEvent(event)
        }
        return true
    }

    override fun onTouchEvent(me: MotionEvent): Boolean {
        // Convert multi-pointer up/down events to single up/down events to
        // deal with the typical multi-pointer behavior of two-thumb typing
        val pointerCount = me.pointerCount
        val action = me.action
        var result: Boolean
        val now = me.eventTime
        if (pointerCount != mOldPointerCount) {
            if (pointerCount == 1) {
                // Send a down event for the latest pointer
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, me.x, me.y, me.metaState)
                result = onModifiedTouchEvent(down)
                down.recycle()
                // If it's an up action, then deliver the up as well.
                if (action == MotionEvent.ACTION_UP) {
                    result = onModifiedTouchEvent(me)
                }
            } else {
                // Send an up event for the last pointer
                val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, mOldPointerX, mOldPointerY, me.metaState)
                result = onModifiedTouchEvent(up)
                up.recycle()
            }
        } else {
            if (pointerCount == 1) {
                result = onModifiedTouchEvent(me)
                mOldPointerX = me.x
                mOldPointerY = me.y
            } else {
                // Don't do anything when 2 pointers are down and moving.
                result = true
            }
        }

        mOldPointerCount = pointerCount

        // handle moving between alternative popup characters by swiping
        if (mPopupKeyboard.isShowing) {
            when (action) {
                MotionEvent.ACTION_MOVE -> {
                    if (mMiniKeyboard != null) {
                        val coords = intArrayOf(0, 0)
                        mMiniKeyboard!!.getLocationOnScreen(coords)
                        val keysCnt = mMiniKeyboard!!.mKeys.size
                        val lastRowKeyCount = if (keysCnt > MAX_KEYS_PER_MINI_ROW) {
                            Math.max(keysCnt % MAX_KEYS_PER_MINI_ROW, 1)
                        } else {
                            keysCnt
                        }

                        val widthPerKey = if (keysCnt > MAX_KEYS_PER_MINI_ROW) {
                            mMiniKeyboard!!.width / MAX_KEYS_PER_MINI_ROW
                        } else {
                            mMiniKeyboard!!.width / lastRowKeyCount
                        }

                        var selectedKeyIndex = Math.floor((me.x - coords[0]) / widthPerKey.toDouble()).toInt()
                        if (keysCnt > MAX_KEYS_PER_MINI_ROW) {
                            selectedKeyIndex = Math.max(0, selectedKeyIndex)
                            selectedKeyIndex += MAX_KEYS_PER_MINI_ROW
                        }

                        selectedKeyIndex = Math.max(0, Math.min(selectedKeyIndex, keysCnt - 1))
                        if (selectedKeyIndex != mMiniKeyboardSelectedKeyIndex) {
                            for (i in 0 until keysCnt) {
                                mMiniKeyboard!!.mKeys[i].focused = i == selectedKeyIndex
                            }
                            mMiniKeyboardSelectedKeyIndex = selectedKeyIndex
                            mMiniKeyboard!!.invalidateAllKeys()
                        }

                        if (coords[0] - me.x > mPopupMaxMoveDistance ||                                 // left
                            me.x - (coords[0] + mMiniKeyboard!!.width) > mPopupMaxMoveDistance          // right
                        ) {
                            dismissPopupKeyboard()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mMiniKeyboard?.mKeys?.firstOrNull { it.focused }?.apply {
                        mOnKeyboardActionListener!!.onKey(codes[0], codes.toIntArray())
                    }
                    mMiniKeyboardSelectedKeyIndex = -1
                    dismissPopupKeyboard()
                }
            }
        }

        return result
    }

    private fun onModifiedTouchEvent(me: MotionEvent): Boolean {
        var touchX = me.x.toInt()
        var touchY = me.y.toInt()
        if (touchY >= -mVerticalCorrection) {
            touchY += mVerticalCorrection
        }

        val action = me.action
        val eventTime = me.eventTime
        val keyIndex = getKeyIndices(touchX, touchY, null)

        // Ignore all motion events until a DOWN.
        if (mAbortKey && action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_CANCEL) {
            return true
        }

        // Needs to be called after the gesture detector gets a turn, as it may have
        // displayed the mini keyboard
        if (mMiniKeyboardOnScreen && action != MotionEvent.ACTION_CANCEL) {
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mAbortKey = false
                mLastCodeX = touchX
                mLastCodeY = touchY
                mLastKeyTime = 0
                mCurrentKeyTime = 0
                mLastKey = NOT_A_KEY
                mCurrentKey = keyIndex
                mDownTime = me.eventTime
                mLastMoveTime = mDownTime
                checkMultiTap(eventTime, keyIndex)

                val onPressKey = if (keyIndex != NOT_A_KEY) {
                    mKeys[keyIndex].codes[0]
                } else {
                    0
                }

                mOnKeyboardActionListener!!.onPress(onPressKey)

                var wasHandled = false
                if (mCurrentKey >= 0 && mKeys[mCurrentKey].repeatable) {
                    mRepeatKeyIndex = mCurrentKey

                    val msg = mHandler!!.obtainMessage(MSG_REPEAT)
                    mHandler!!.sendMessageDelayed(msg, REPEAT_START_DELAY.toLong())
                    // if the user long presses Space, move the cursor after swipine left/right
                    if (mKeys[mCurrentKey].codes.firstOrNull() == MyKeyboard.KEYCODE_SPACE) {
                        mLastSpaceMoveX = -1
                    } else {
                        repeatKey(true)
                    }

                    // Delivering the key could have caused an abort
                    if (mAbortKey) {
                        mRepeatKeyIndex = NOT_A_KEY
                        wasHandled = true
                    }
                }

                if (!wasHandled && mCurrentKey != NOT_A_KEY) {
                    val msg = mHandler!!.obtainMessage(MSG_LONGPRESS, me)
                    mHandler!!.sendMessageDelayed(msg, LONGPRESS_TIMEOUT.toLong())
                }

                if (mPopupParent.id != R.id.mini_keyboard_view) {
                    showPreview(keyIndex)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                var continueLongPress = false
                if (keyIndex != NOT_A_KEY) {
                    if (mCurrentKey == NOT_A_KEY) {
                        mCurrentKey = keyIndex
                        mCurrentKeyTime = eventTime - mDownTime
                    } else {
                        if (keyIndex == mCurrentKey) {
                            mCurrentKeyTime += eventTime - mLastMoveTime
                            continueLongPress = true
                        } else if (mRepeatKeyIndex == NOT_A_KEY) {
                            resetMultiTap()
                            mLastKey = mCurrentKey
                            mLastCodeX = mLastX
                            mLastCodeY = mLastY
                            mLastKeyTime = mCurrentKeyTime + eventTime - mLastMoveTime
                            mCurrentKey = keyIndex
                            mCurrentKeyTime = 0
                        }
                    }
                }

                if (mIsLongPressingSpace) {
                    if (mLastSpaceMoveX == -1) {
                        mLastSpaceMoveX = mLastX
                    }

                    val diff = mLastX - mLastSpaceMoveX

                    if (diff < -mSpaceMoveThreshold) {
                        for (i in diff / mSpaceMoveThreshold until 0) {
                            mOnKeyboardActionListener?.moveCursorLeft()
                        }
                        mLastSpaceMoveX = mLastX
                    } else if (diff > mSpaceMoveThreshold) {
                        for (i in 0 until diff / mSpaceMoveThreshold) {
                            mOnKeyboardActionListener?.moveCursorRight()
                        }
                        mLastSpaceMoveX = mLastX
                    }
                } else if (!continueLongPress) {
                    // Cancel old longpress
                    mHandler!!.removeMessages(MSG_LONGPRESS)
                    // Start new longpress if key has changed
                    if (keyIndex != NOT_A_KEY) {
                        val msg = mHandler!!.obtainMessage(MSG_LONGPRESS, me)
                        mHandler!!.sendMessageDelayed(msg, LONGPRESS_TIMEOUT.toLong())
                    }

                    showPreview(mCurrentKey)
                    mLastMoveTime = eventTime
                }
            }
            MotionEvent.ACTION_UP -> {
                mLastSpaceMoveX = 0
                removeMessages()
                if (keyIndex == mCurrentKey) {
                    mCurrentKeyTime += eventTime - mLastMoveTime
                } else {
                    resetMultiTap()
                    mLastKey = mCurrentKey
                    mLastKeyTime = mCurrentKeyTime + eventTime - mLastMoveTime
                    mCurrentKey = keyIndex
                    mCurrentKeyTime = 0
                }

                if (mCurrentKeyTime < mLastKeyTime && mCurrentKeyTime < DEBOUNCE_TIME && mLastKey != NOT_A_KEY) {
                    mCurrentKey = mLastKey
                    touchX = mLastCodeX
                    touchY = mLastCodeY
                }
                showPreview(NOT_A_KEY)
                Arrays.fill(mKeyIndices, NOT_A_KEY)
                // If we're not on a repeating key (which sends on a DOWN event)
                if (mRepeatKeyIndex == NOT_A_KEY && !mMiniKeyboardOnScreen && !mAbortKey) {
                    detectAndSendKey(mCurrentKey, touchX, touchY, eventTime)
                }

                if (mKeys.getOrNull(mCurrentKey)?.codes?.firstOrNull() == MyKeyboard.KEYCODE_SPACE && !mIsLongPressingSpace) {
                    detectAndSendKey(mCurrentKey, touchX, touchY, eventTime)
                }

                invalidateKey(keyIndex)
                mRepeatKeyIndex = NOT_A_KEY
                mOnKeyboardActionListener!!.onActionUp()
                mIsLongPressingSpace = false
            }
            MotionEvent.ACTION_CANCEL -> {
                mIsLongPressingSpace = false
                mLastSpaceMoveX = 0
                removeMessages()
                dismissPopupKeyboard()
                mAbortKey = true
                showPreview(NOT_A_KEY)
                invalidateKey(mCurrentKey)
            }
        }

        mLastX = touchX
        mLastY = touchY
        return true
    }

    private fun repeatKey(initialCall: Boolean): Boolean {
        val key = mKeys[mRepeatKeyIndex]
        if (!initialCall && key.codes.firstOrNull() == MyKeyboard.KEYCODE_SPACE) {
            mIsLongPressingSpace = true
        } else {
            detectAndSendKey(mCurrentKey, key.x, key.y, mLastTapTime)
        }
        return true
    }

    private fun closing() {
        if (mPreviewPopup.isShowing) {
            mPreviewPopup.dismiss()
        }
        removeMessages()
        dismissPopupKeyboard()
        mBuffer = null
        mCanvas = null
        mMiniKeyboardCache.clear()
    }

    private fun removeMessages() {
        mHandler?.apply {
            removeMessages(MSG_REPEAT)
            removeMessages(MSG_LONGPRESS)
            removeMessages(MSG_SHOW_PREVIEW)
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    private fun dismissPopupKeyboard() {
        if (mPopupKeyboard.isShowing) {
            mPopupKeyboard.dismiss()
            mMiniKeyboardOnScreen = false
            invalidateAllKeys()
        }
    }

    private fun resetMultiTap() {
        mLastSentIndex = NOT_A_KEY
        mTapCount = 0
        mLastTapTime = -1
        mInMultiTap = false
    }

    private fun checkMultiTap(eventTime: Long, keyIndex: Int) {
        if (keyIndex == NOT_A_KEY) return
        val key = mKeys[keyIndex]
        if (key.codes.size > 1) {
            mInMultiTap = true
            if (eventTime < mLastTapTime + MULTITAP_INTERVAL && keyIndex == mLastSentIndex) {
                mTapCount = (mTapCount + 1) % key.codes.size
                return
            } else {
                mTapCount = -1
                return
            }
        }

        if (eventTime > mLastTapTime + MULTITAP_INTERVAL || keyIndex != mLastSentIndex) {
            resetMultiTap()
        }
    }
}
