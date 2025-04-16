package com.tokenautocomplete

import android.content.Context
import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.NoCopySpan
import android.text.Selection
import android.text.SpanWatcher
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.ListView
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.annotation.UiThread
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import java.io.Serializable
import java.lang.reflect.ParameterizedType
import kotlin.math.max

/**
 * GMail style auto complete view with easy token customization
 * override getViewForObject to provide your token view
 * <br></br>
 * Created by mgod on 9/12/13.
 *
 * @author mgod
 */
abstract class TokenCompleteTextView<T: Any> : AppCompatAutoCompleteTextView,
    OnEditorActionListener, ViewSpan.Layout {
    private var tokenizer: Tokenizer = CharacterTokenizer(mutableListOf(',', ';'), ",")
    private var selectedObject: T? = null
    private var listener: TokenListener<T>? = null
    private var spanWatcher = TokenSpanWatcher()
    private var textWatcher = TokenTextWatcher()
    private var countSpan = CountSpan()
    private var hiddenContent: SpannableStringBuilder? = null
    private var lastLayout: Layout? = null
    private var initialized = false
    private var performBestGuess = true
    private var savingState = false
    private var shouldFocusNext = false
    private var allowCollapse = true
    private var internalEditInProgress = false

    private var tokenLimit = -1

    @Transient
    private var lastCompletionText: String? = null

    /**
     * Add the TextChangedListeners
     */
    private fun addListeners() {
        text?.apply {
            setSpan(spanWatcher, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }?.also { addTextChangedListener(textWatcher) }
    }

    /**
     * Remove the TextChangedListeners
     */
    private fun removeListeners() {
        val text = text
        if (text != null) {
            val spanWatchers = text.getSpans(
                0, text.length,
                TokenSpanWatcher::class.java
            )
            for (watcher in spanWatchers) {
                text.removeSpan(watcher)
            }
            removeTextChangedListener(textWatcher)
        }
    }

    /**
     * Initialise the variables and various listeners
     */
    private fun init() {
        if (initialized) return

        // Initialise variables
        checkNotNull(text)
        hiddenContent = null

        // Initialise TextChangedListeners
        addListeners()

        setTextIsSelectable(false)
        isLongClickable = false

        //In theory, get the soft keyboard to not supply suggestions. very unreliable
        inputType = inputType or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
        setHorizontallyScrolling(false)

        // Listen to IME action keys
        setOnEditorActionListener(this)

        // Initialise the text filter (listens for the split chars)
        filters = arrayOf<InputFilter>(object : InputFilter {
            override fun filter(
                source: CharSequence, start: Int, end: Int,
                dest: Spanned, destinationStart: Int, destinationEnd: Int
            ): CharSequence? {
                if (internalEditInProgress) {
                    return null
                }

                // Token limit check
                if (tokenLimit != -1 && objects.size == tokenLimit) {
                    return ""
                }

                //Detect split characters, remove them and complete the current token instead
                // We only want to handle the case where the user inputs a single split character here
                if (source.length == 1 && tokenizer.containsTokenTerminator(source)) {
                    performCompletion()
                    return ""
                }

                return null
            }
        })

        initialized = true
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        init()
    }

    override fun performFiltering(text: CharSequence, keyCode: Int) {
        filter?.filter(currentCompletionText(), this)
    }

    private fun setTokenizer(t: Tokenizer) {
        tokenizer = t
    }

    /**
     * Set the listener that will be notified of changes in the Token list
     *
     * @param l The TokenListener
     */
    fun setTokenListener(l: TokenListener<T>) {
        listener = l
    }

    /**
     * Override if you want to prevent a token from being added. Defaults to false.
     * @param token the token to check
     * @return true if the token should not be added, false if it's ok to add it.
     */
    open fun shouldIgnoreToken(@Suppress("unused") token: T): Boolean {
        return false
    }

    /**
     * Override if you want to prevent a token from being removed. Defaults to true.
     * @param token the token to check
     * @return false if the token should not be removed, true if it's ok to remove it.
     */
    open fun isTokenRemovable(@Suppress("unused") token: T): Boolean {
        return true
    }

    val objects: List<T>
        /**
         * Get the list of Tokens
         *
         * @return List of tokens
         */
        get() {
            val objects = ArrayList<T>()
            val text = hiddenContent ?: text
            for (span in getSpans(0, text.length)) {
                objects.add(span.token)
            }
            return objects
        }

    val contentText: CharSequence
        /**
         * Get the content entered in the text field, including hidden text when ellipsized
         *
         * @return CharSequence of the entered content
         */
        get() = hiddenContent ?: text

    /**
     * Set whether we try to guess an entry from the autocomplete spinner or just use the
     * defaultObject implementation for inline token completion.
     *
     * @param guess true to enable guessing
     */
    fun performBestGuess(guess: Boolean) {
        performBestGuess = guess
    }

    /**
     * Set whether the view should collapse to a single line when it loses focus.
     *
     * @param allowCollapse true if it should collapse
     */
    fun allowCollapse(allowCollapse: Boolean) {
        this.allowCollapse = allowCollapse
    }

    /**
     * Set a number of tokens limit.
     *
     * @param tokenLimit The number of tokens permitted. -1 value disables limit.
     */
    @Suppress("unused")
    fun setTokenLimit(tokenLimit: Int) {
        this.tokenLimit = tokenLimit
    }

    /**
     * A token view for the object
     *
     * @param object the object selected by the user from the list
     * @return a view to display a token in the text field for the object
     */
    protected abstract fun getViewForObject(`object`: T): View

    /**
     * Provides a default completion when the user hits , and there is no item in the completion
     * list
     *
     * @param completionText the current text we are completing against
     * @return a best guess for what the user meant to complete or null if you don't want a guess
     */
    protected abstract fun defaultObject(completionText: String): T?

    @get:Suppress("unused")
    val textForAccessibility: CharSequence
        /**
         * Correctly build accessibility string for token contents
         *
         * This seems to be a hidden API, but there doesn't seem to be another reasonable way
         * @return custom string for accessibility
         */
        get() {
            if (objects.isEmpty()) {
                return text
            }

            var description = SpannableStringBuilder()
            val text = text
            var selectionStart = -1
            var selectionEnd = -1
            var i: Int
            //Need to take the existing tet buffer and
            // - replace all tokens with a decent string representation of the object
            // - set the selection span to the corresponding location in the new CharSequence
            i = 0
            while (i < text.length) {
                //See if this is where we should start the selection
                val origSelectionStart = Selection.getSelectionStart(text)
                if (i == origSelectionStart) {
                    selectionStart = description.length
                }
                val origSelectionEnd = Selection.getSelectionEnd(text)
                if (i == origSelectionEnd) {
                    selectionEnd = description.length
                }

                //Replace token spans
                val tokens = getSpans(i, i)
                if (tokens.isNotEmpty()) {
                    val token = tokens[0]
                    description = description.append(tokenizer.wrapTokenValue(token.token.toString()))
                    i = text.getSpanEnd(token)
                    ++i
                    continue
                }

                description = description.append(text.subSequence(i, i + 1))
                ++i
            }

            val origSelectionStart = Selection.getSelectionStart(text)
            if (i == origSelectionStart) {
                selectionStart = description.length
            }
            val origSelectionEnd = Selection.getSelectionEnd(text)
            if (i == origSelectionEnd) {
                selectionEnd = description.length
            }

            if (selectionStart >= 0 && selectionEnd >= 0) {
                Selection.setSelection(description, selectionStart, selectionEnd)
            }

            return description
        }

    /**
     * Clear the completion text only.
     */
    @Suppress("unused")
    fun clearCompletionText() {
        //Respect currentCompletionText in case hint is visible or if other checks are added.
        if (currentCompletionText().isEmpty()) {
            return
        }

        val currentRange = currentCandidateTokenRange
        internalEditInProgress = true
        text.delete(currentRange.start, currentRange.end)
        internalEditInProgress = false
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val text = textForAccessibility
            event.apply {
                fromIndex = Selection.getSelectionStart(text)
                toIndex = Selection.getSelectionEnd(text)
                itemCount = text.length
            }

        }
    }

    private val currentCandidateTokenRange: Range
        get() {
            val editable = text
            val cursorEndPosition = selectionEnd
            var candidateStringStart = 0
            var candidateStringEnd = editable.length

            //We want to find the largest string that contains the selection end that is not already tokenized
            val spans = getSpans(0, editable.length)
            for (span in spans) {
                val spanEnd = editable.getSpanEnd(span)
                if (spanEnd in (candidateStringStart + 1)..cursorEndPosition) {
                    candidateStringStart = spanEnd
                }
                val spanStart = editable.getSpanStart(span)
                if (candidateStringEnd > spanStart && cursorEndPosition <= spanEnd) {
                    candidateStringEnd = spanStart
                }
            }
            if (candidateStringEnd < candidateStringStart) {
                return Range(cursorEndPosition, cursorEndPosition)
            }
            return Range(candidateStringStart, candidateStringEnd)
        }

    /**
     * Override if you need custom logic to provide a sting representation of a token
     * @param token the token to convert
     * @return the string representation of the token. Defaults to [Object.toString]
     */
    private fun tokenToString(token: T): CharSequence {
        return token.toString()
    }

    protected fun currentCompletionText(): String {
        val editable = text
        val currentRange = currentCandidateTokenRange

        val result = TextUtils.substring(editable, currentRange.start, currentRange.end)
        Log.d(TAG, "Current completion text: $result")
        return result
    }

    private fun maxTextWidth(): Float {
        return (width - paddingLeft - paddingRight).toFloat()
    }

    override fun getMaxViewSpanWidth(): Int {
        return maxTextWidth().toInt()
    }

    fun redrawTokens() {
        // There's no straight-forward way to convince the widget to redraw the text and spans. We trigger a redraw by
        // making an invisible change (either adding or removing a dummy span).

        val text = text ?: return

        val textLength = text.length
        val dummySpans = text.getSpans(0, textLength, DummySpan::class.java)
        if (dummySpans.isNotEmpty()) {
            text.removeSpan(DummySpan.INSTANCE)
        } else {
            text.setSpan(DummySpan.INSTANCE, 0, textLength, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }

    override fun enoughToFilter(): Boolean {
        val cursorPosition = selectionEnd

        if (cursorPosition < 0) {
            return false
        }

        val currentCandidateRange = currentCandidateTokenRange

        //Don't allow 0 length entries to filter
        return currentCandidateRange.length() >= max(threshold.toDouble(), 1.0)
    }

    override fun performCompletion() {
        if ((adapter == null || listSelection == ListView.INVALID_POSITION) && enoughToFilter()) {
            val bestGuess =
                if (adapter != null && adapter.count > 0 && performBestGuess) {
                    adapter.getItem(0)
                } else {
                    defaultObject(currentCompletionText())
                }
            if (bestGuess != null) replaceText(convertSelectionToString(bestGuess))
        } else {
            super.performCompletion()
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val superConn = super.onCreateInputConnection(outAttrs)
        if (superConn != null) {
            val conn = TokenInputConnection(superConn, true)
            outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            return conn
        } else {
            return null
        }
    }

    /**
     * "Type-safe" Kotlin wrapper around getSpans for TokenImageSpan.
     */
    private fun getSpans(i: Int, j: Int) =
        text.getSpans(i, j, TokenImageSpan::class.java) as Array<TokenCompleteTextView<T>.TokenImageSpan>

    /**
     * Create a token and hide the keyboard when the user sends the DONE IME action
     * Use IME_NEXT if you want to create a token and go to the next field
     */
    private fun handleDone() {
        // Attempt to complete the current token token
        performCompletion()

        // Hide the keyboard
        val imm = context.getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyUp(keyCode, event)
        if (shouldFocusNext) {
            shouldFocusNext = false
            handleDone()
        }
        return handled
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        var handled = false
        when (keyCode) {
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> if (event.hasNoModifiers()) {
                shouldFocusNext = true
                handled = true
            }

            KeyEvent.KEYCODE_DEL -> handled = !canDeleteSelection(1)
        }

        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onEditorAction(view: TextView, action: Int, keyEvent: KeyEvent): Boolean {
        if (action == EditorInfo.IME_ACTION_DONE) {
            handleDone()
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val text = text

        var handled = super.onTouchEvent(event)

        if (isFocused && text != null && lastLayout != null && action == MotionEvent.ACTION_UP) {
            val offset = getOffsetForPosition(event.x, event.y)

            if (offset != -1) {
                val links = getSpans(offset, offset)

                if (links.isNotEmpty()) {
                    links[0].onClick()
                    handled = true
                }
            }
        }

        return handled
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        //Never let users select text
        val selEnd = selStart

        val text = text
        if (text != null) {
            //Make sure if we are in a span, we select the spot 1 space after the span end
            val spans = getSpans(selStart, selEnd)
            for (span in spans) {
                val spanEnd = text.getSpanEnd(span)
                if (selStart <= spanEnd && text.getSpanStart(span) < selStart) {
                    if (spanEnd == text.length) setSelection(spanEnd)
                    else setSelection(spanEnd + 1)
                    return
                }
            }
        }

        super.onSelectionChanged(selStart, selEnd)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        lastLayout = layout //Used for checking text positions
    }

    /**
     * Collapse the view by removing all the tokens not on the first line. Displays a "+x" token.
     * Restores the hidden tokens when the view gains focus.
     *
     * @param hasFocus boolean indicating whether we have the focus or not.
     */
    fun performCollapse(hasFocus: Boolean) {
        internalEditInProgress = true
        if (!hasFocus && objects.size > 1) {
            // Display +x thingy/ellipse if appropriate
            val text = text
            if (text != null && hiddenContent == null && lastLayout != null) {
                //Ellipsize copies spans, so we need to stop listening to span changes here

                text.removeSpan(spanWatcher)

                val ellipsized = lastLayout?.let {
                    SpanUtils.ellipsizeWithSpans(
                        countSpan, objects.size,
                        it.paint, text, maxTextWidth()
                    )
                }

                if (ellipsized != null) {
                    hiddenContent = SpannableStringBuilder(text).also {
                        setText(ellipsized)
                        TextUtils.copySpansFrom(
                            ellipsized, 0, ellipsized.length,
                            TokenImageSpan::class.java, getText(), 0
                        )
                        TextUtils.copySpansFrom(
                            text, 0, it.length,
                            TokenImageSpan::class.java, it, 0
                        )
                        it.setSpan(spanWatcher, 0, it.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    }

                } else {
                    getText().setSpan(spanWatcher, 0, getText().length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                }
            }
        } else {
            hiddenContent?.also {
                text = it
                TextUtils.copySpansFrom(
                    it, 0, it.length,
                    TokenImageSpan::class.java, text, 0
                )
            }
            hiddenContent = null

            post { setSelection(text.length) }

            val watchers = text.getSpans(
                0, text.length,
                TokenSpanWatcher::class.java
            )
            if (watchers.isEmpty()) {
                //Span watchers can get removed in setText
                text.setSpan(spanWatcher, 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }
        internalEditInProgress = false
    }

    public override fun onFocusChanged(hasFocus: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(hasFocus, direction, previous)

        // Collapse the view to a single line
        if (allowCollapse) performCollapse(hasFocus)
    }

    override fun convertSelectionToString(`object`: Any): CharSequence {
        selectedObject = `object` as T
        return ""
    }

    protected open fun buildSpanForObject(obj: T): TokenImageSpan {
        val tokenView = getViewForObject(obj)
        return TokenImageSpan(tokenView, obj)
    }

    override fun replaceText(ignore: CharSequence) {
        clearComposingText()

        // Don't build a token for an empty String
        if (selectedObject == null || selectedObject.toString() == "") return

        val tokenSpan = selectedObject?.let(::buildSpanForObject)

        val editable = text
        val candidateRange = currentCandidateTokenRange

        val original = TextUtils.substring(editable, candidateRange.start, candidateRange.end)

        //Keep track of  replacements for a bug workaround
        if (original.isNotEmpty()) {
            lastCompletionText = original
        }

        if (editable != null) {
            internalEditInProgress = true
            if (tokenSpan == null) {
                editable.replace(candidateRange.start, candidateRange.end, "")
            } else if (shouldIgnoreToken(tokenSpan.token)) {
                editable.replace(candidateRange.start, candidateRange.end, "")
                listener?.onTokenIgnored(tokenSpan.token)
            } else {
                val ssb = SpannableStringBuilder(tokenizer.wrapTokenValue(tokenToString(tokenSpan.token)))
                editable.replace(candidateRange.start, candidateRange.end, ssb)
                editable.setSpan(
                    tokenSpan,
                    candidateRange.start,
                    candidateRange.start + ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                editable.insert(candidateRange.start + ssb.length, " ")
            }
            internalEditInProgress = false
        }
    }

    override fun extractText(request: ExtractedTextRequest, outText: ExtractedText): Boolean {
        try {
            return super.extractText(request, outText)
        } catch (ex: IndexOutOfBoundsException) {
            Log.d(TAG, "extractText hit IndexOutOfBoundsException. This may be normal.", ex)
            return false
        }
    }

    /**
     * Append a token object to the object list. May only be called from the main thread.
     *
     * @param object the object to add to the displayed tokens
     */
    @UiThread
    fun addObjectSync(`object`: T?) {
        if (`object` == null) return
        if (shouldIgnoreToken(`object`)) {
            listener?.onTokenIgnored(`object`)
            return
        }
        if (tokenLimit != -1 && objects.size == tokenLimit) return
        insertSpan(buildSpanForObject(`object`))
        if (text != null && isFocused) setSelection(text.length)
    }

    /**
     * Append a token object to the object list. Object will be added on the main thread.
     *
     * @param object the object to add to the displayed tokens
     */
    fun addObjectAsync(`object`: T) {
        post { addObjectSync(`object`) }
    }

    /**
     * Remove an object from the token list. Will remove duplicates if present or do nothing if no
     * object is present in the view. Uses [Object.equals] to find objects. May only
     * be called from the main thread
     *
     * @param object object to remove, may be null or not in the view
     */
    @UiThread
    fun removeObjectSync(`object`: T) {
        //To make sure all the appropriate callbacks happen, we just want to piggyback on the
        //existing code that handles deleting spans when the text changes
        val texts = ArrayList<Editable>()
        //If there is hidden content, it's important that we update it first
        hiddenContent?.let { texts.add(it) }
        text?.let { texts.add(it) }

        // If the object is currently visible, remove it
        for (text in texts) {
            val spans = getSpans(0, text.length)
            for (span in spans) {
                if (span.token == `object`) {
                    removeSpan(text, span)
                }
            }
        }

        updateCountSpan()
    }

    /**
     * Remove an object from the token list. Will remove duplicates if present or do nothing if no
     * object is present in the view. Uses [Object.equals] to find objects. Object
     * will be added on the main thread
     *
     * @param object object to remove, may be null or not in the view
     */
    fun removeObjectAsync(`object`: T) {
        post { removeObjectSync(`object`) }
    }

    /**
     * Remove all objects from the token list. Objects will be removed on the main thread.
     */
    fun clearAsync() {
        post {
            for (`object` in objects) {
                removeObjectSync(`object`)
            }
        }
    }

    /**
     * Set the count span the current number of hidden objects
     */
    private fun updateCountSpan() {
        val text = text

        val visibleCount = getSpans(0, getText().length).size
        countSpan.setCount(objects.size - visibleCount)

        val spannedCountText = SpannableStringBuilder(countSpan.countText)
        spannedCountText.setSpan(countSpan, 0, spannedCountText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        internalEditInProgress = true
        val countStart = text.getSpanStart(countSpan)
        if (countStart != -1) {
            //Span is in the text, replace existing text
            //This will also remove the span if the count is 0
            text.replace(countStart, text.getSpanEnd(countSpan), spannedCountText)
        } else {
            text.append(spannedCountText)
        }

        internalEditInProgress = false
    }

    /**
     * Remove a span from the current EditText and fire the appropriate callback
     *
     * @param text Editable to remove the span from
     * @param span TokenImageSpan to be removed
     */
    private fun removeSpan(text: Editable, span: TokenImageSpan) {
        //We usually add whitespace after a token, so let's try to remove it as well if it's present
        var end = text.getSpanEnd(span)
        if (end < text.length && text[end] == ' ') {
            end += 1
        }

        internalEditInProgress = true
        text.delete(text.getSpanStart(span), end)
        internalEditInProgress = false

        if (allowCollapse && !isFocused) {
            updateCountSpan()
        }
    }

    /**
     * Insert a new span for an Object
     *
     * @param tokenSpan span to insert
     */
    private fun insertSpan(tokenSpan: TokenImageSpan) {
        val ssb = tokenizer.wrapTokenValue(tokenToString(tokenSpan.token))

        val editable = text ?: return
        val hiddenContent = hiddenContent

        // If we haven't hidden any objects yet, we can try adding it
        if (hiddenContent == null) {
            internalEditInProgress = true
            var offset = editable.length

            val currentRange = currentCandidateTokenRange
            if (currentRange.length() > 0) {
                // The user has entered some text that has not yet been tokenized.
                // Find the beginning of this text and insert the new token there.
                offset = currentRange.start
            }

            editable.insert(offset, ssb)
            editable.insert(offset + ssb.length, " ")
            editable.setSpan(tokenSpan, offset, offset + ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            internalEditInProgress = false
        } else {
            val tokenText = tokenizer.wrapTokenValue(tokenToString(tokenSpan.token))
            hiddenContent.apply {
                append(tokenText)
                append(" ")
                setSpan(tokenSpan, length, length + tokenText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    open inner class TokenImageSpan(d: View?, val token: T) : ViewSpan(d, this@TokenCompleteTextView),
        NoCopySpan {
        open fun onClick() {
            val text = text ?: return

            if (selectionStart != text.getSpanEnd(this)) {
                //Make sure the selection is not in the middle of the span
                setSelection(text.getSpanEnd(this))
            }
        }
    }

    interface TokenListener<T> {
        fun onTokenAdded(token: T)
        fun onTokenRemoved(token: T)
        fun onTokenIgnored(token: T)
    }

    inner class TokenSpanWatcher : SpanWatcher {
        override fun onSpanAdded(text: Spannable, what: Any, start: Int, end: Int) {
            if (what is TokenCompleteTextView<*>.TokenImageSpan && !savingState) {
                // If we're not focused: collapse the view if necessary
                if (!isFocused && allowCollapse) performCollapse(false)

                listener?.onTokenAdded(what.token as T)
            }
        }

        override fun onSpanRemoved(text: Spannable, what: Any, start: Int, end: Int) {
            if (what is TokenCompleteTextView<*>.TokenImageSpan && !savingState) {
                listener?.onTokenRemoved(what.token as T)
            }
        }

        override fun onSpanChanged(
            text: Spannable, what: Any,
            oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int
        ) {
        }
    }

    private inner class TokenTextWatcher : TextWatcher {
        var spansToRemove: ArrayList<TokenImageSpan> = ArrayList()

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            // count > 0 means something will be deleted
            if (count > 0 && text != null) {
                val text = text

                val end = start + count

                val spans = getSpans(start, end)

                //NOTE: I'm not completely sure this won't cause problems if we get stuck in a text changed loop
                //but it appears to work fine. Spans will stop getting removed if this breaks.
                val spansToRemove = ArrayList<TokenImageSpan>()
                for (token in spans) {
                    if (text.getSpanStart(token) < end && start < text.getSpanEnd(token)) {
                        spansToRemove.add(token)
                    }
                }
                this.spansToRemove = spansToRemove
            }
        }

        override fun afterTextChanged(text: Editable) {
            val spansCopy = ArrayList(spansToRemove)
            spansToRemove.clear()
            for (token in spansCopy) {
                //Only remove it if it's still present
                if (text.getSpanStart(token) != -1 && text.getSpanEnd(token) != -1) {
                    removeSpan(text, token)
                }
            }
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        }
    }

    private val serializableObjects: List<Serializable>
        get() {
            val serializables: MutableList<Serializable> =
                ArrayList()
            for (obj in objects) {
                if (obj is Serializable) {
                    serializables.add(obj as Serializable)
                } else {
                    Log.e(TAG, "Unable to save '$obj'")
                }
            }
            if (serializables.size != objects.size) {
                val message = """
                     You should make your objects Serializable or Parcelable or
                     override getSerializableObjects and convertSerializableArrayToObjectArray
                     """.trimIndent()
                Log.e(TAG, message)
            }

            return serializables
        }

    private fun convertSerializableObjectsToTypedObjects(s: List<*>): List<T> {
        return s as List<T>
    }

    //Used to determine if we can use the Parcelable interface
    private fun reifyParameterizedTypeClass(): Class<*> {
        //Borrowed from http://codyaray.com/2013/01/finding-generic-type-parameters-with-guava

        //Figure out what class of objects we have

        var viewClass: Class<*>? = javaClass
        while (viewClass!!.superclass != TokenCompleteTextView::class.java) {
            viewClass = viewClass.superclass
        }

        // This operation is safe. Because viewClass is a direct sub-class, getGenericSuperclass() will
        // always return the Type of this class. Because this class is parameterized, the cast is safe
        val superclass = viewClass.genericSuperclass as ParameterizedType
        val type = superclass.actualTypeArguments[0]
        return type as Class<*>
    }

    override fun onSaveInstanceState(): Parcelable? {
        //We don't want to save the listeners as part of the parent
        //onSaveInstanceState, so remove them first
        removeListeners()

        //Apparently, saving the parent state on 2.3 mutates the spannable
        //prevent this mutation from triggering add or removes of token objects ~mgod
        savingState = true
        val superState = super.onSaveInstanceState()
        savingState = false
        val state = SavedState(superState)

        state.allowCollapse = allowCollapse
        state.performBestGuess = performBestGuess
        val parameterizedClass = reifyParameterizedTypeClass()
        //Our core array is Parcelable, so use that interface
        if (Parcelable::class.java.isAssignableFrom(parameterizedClass)) {
            state.parcelableClassName = parameterizedClass.name
            state.baseObjects = objects
        } else {
            //Fallback on Serializable
            state.parcelableClassName = SavedState.SERIALIZABLE_PLACEHOLDER
            state.baseObjects = serializableObjects
        }
        state.tokenizer = tokenizer

        //So, when the screen is locked or some other system event pauses execution,
        //onSaveInstanceState gets called, but it won't restore state later because the
        //activity is still in memory, so make sure we add the listeners again
        //They should not be restored in onInstanceState if the app is actually killed
        //as we removed them before the parent saved instance state, so our adding them in
        //onRestoreInstanceState is good.
        addListeners()

        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }

        super.onRestoreInstanceState(state.superState)

        allowCollapse = state.allowCollapse
        performBestGuess = state.performBestGuess
        tokenizer = state.tokenizer
        addListeners()
        val objects = if (SavedState.SERIALIZABLE_PLACEHOLDER == state.parcelableClassName) {
            convertSerializableObjectsToTypedObjects(state.baseObjects)
        } else {
            state.baseObjects as List<T>
        }

        //TODO: change this to keep object spans in the correct locations based on ranges.
        for (obj in objects) {
            addObjectSync(obj)
        }

        // Collapse the view if necessary
        if (!isFocused && allowCollapse) {
            post { //Resize the view and display the +x if appropriate
                performCollapse(isFocused)
            }
        }
    }

    /**
     * Handle saving the token state
     */
    private class SavedState : BaseSavedState {
        var allowCollapse: Boolean = false
        var performBestGuess: Boolean = false
        var parcelableClassName: String = ""
        lateinit var baseObjects: List<*>
        var tokenizerClassName: String = ""
        lateinit var tokenizer: Tokenizer

        constructor(`in`: Parcel) : super(`in`) {
            allowCollapse = `in`.readInt() != 0
            performBestGuess = `in`.readInt() != 0
            parcelableClassName = `in`.readString() ?: ""
            if (SERIALIZABLE_PLACEHOLDER == parcelableClassName) {
                baseObjects = `in`.readSerializable() as ArrayList<*>
            } else {
                try {
                    val loader = Class.forName(parcelableClassName).classLoader
                    baseObjects = `in`.readArrayList(loader)!!
                } catch (ex: ClassNotFoundException) {
                    //This should really never happen, class had to be available to get here
                    throw RuntimeException(ex)
                }
            }
            tokenizerClassName = `in`.readString() ?: ""
            try {
                val loader = Class.forName(tokenizerClassName).classLoader
                tokenizer = `in`.readParcelable(loader)!!
            } catch (ex: ClassNotFoundException) {
                //This should really never happen, class had to be available to get here
                throw RuntimeException(ex)
            }
        }

        constructor(superState: Parcelable?) : super(superState)

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(if (allowCollapse) 1 else 0)
            out.writeInt(if (performBestGuess) 1 else 0)
            if (SERIALIZABLE_PLACEHOLDER == parcelableClassName) {
                out.writeString(SERIALIZABLE_PLACEHOLDER)
                out.writeSerializable(baseObjects as Serializable?)
            } else {
                out.writeString(parcelableClassName)
                out.writeList(baseObjects)
            }
            out.writeString(tokenizer.javaClass.canonicalName)
            out.writeParcelable(tokenizer, 0)
        }

        override fun toString(): String {
            val str = ("TokenCompleteTextView.SavedState{"
                + Integer.toHexString(System.identityHashCode(this))
                + " tokens=" + baseObjects)
            return "$str}"
        }

        companion object {
            const val SERIALIZABLE_PLACEHOLDER: String = "Serializable"

            @JvmField
            val CREATOR
                : Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(`in`: Parcel): SavedState {
                    return SavedState(`in`)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    /**
     * Checks if selection can be deleted. This method is called from TokenInputConnection .
     * @param beforeLength the number of characters before the current selection end to check
     * @return true if there are no non-deletable pieces of the section
     */
    fun canDeleteSelection(beforeLength: Int): Boolean {
        if (objects.isEmpty()) return true

        // if beforeLength is 1, we either have no selection or the call is coming from OnKey Event.
        // In these scenarios, getSelectionStart() will return the correct value.
        val endSelection = selectionEnd
        val startSelection = if (beforeLength == 1) selectionStart else endSelection - beforeLength

        val spans = getSpans(0, text.length)

        // Iterate over all tokens and allow the deletion
        // if there are no tokens not removable in the selection
        for (span in spans) {
            val startTokenSelection = text.getSpanStart(span)
            val endTokenSelection = text.getSpanEnd(span)

            // moving on, no need to check this token
            if (isTokenRemovable(span.token)) continue

            if (startSelection == endSelection) {
                // Delete single
                if (endTokenSelection + 1 == endSelection) {
                    return false
                }
            } else {
                // Delete range
                // Don't delete if a non removable token is in range
                if (startSelection <= startTokenSelection
                    && endTokenSelection + 1 <= endSelection
                ) {
                    return false
                }
            }
        }
        return true
    }

    private inner class TokenInputConnection(target: InputConnection?, mutable: Boolean) :
        InputConnectionWrapper(target, mutable) {
        // This will fire if the soft keyboard delete key is pressed.
        // The onKeyPressed method does not always do this.
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Shouldn't be able to delete any text with tokens that are not removable
            if (!canDeleteSelection(beforeLength)) return false

            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun setComposingText(newText: CharSequence?, newCursorPosition: Int): Boolean {
            //There's an issue with some keyboards where they will try to insert the first word
            //of the prefix as the composing text
            var text = newText
            val hint = hint
            if (hint != null && text != null) {
                val firstWord = hint.toString().trim { it <= ' ' }.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()[0]
                if (firstWord.isNotEmpty() && firstWord == text.toString()) {
                    text = "" //It was trying to use th hint, so clear that text
                }
            }

            //Also, some keyboards don't correctly respect the replacement if the replacement
            //is the same number of characters as the replacement span
            //We need to ignore this value if it's available
            if (lastCompletionText != null && text != null && text.length == lastCompletionText!!.length + 1 &&
                text.toString().startsWith(lastCompletionText!!)
            ) {
                text = text.subSequence(text.length - 1, text.length)
                lastCompletionText = null
            }

            return super.setComposingText(text, newCursorPosition)
        }
    }

    companion object {
        //Logging
        const val TAG: String = "TokenAutoComplete"
    }
}
