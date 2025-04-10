package com.fsck.k9.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.ContactsContract
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ListPopupWindow
import android.widget.ListView
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import app.k9mail.legacy.di.DI.get
import com.fsck.k9.FontSizes
import com.fsck.k9.K9.isShowCorrespondentNames
import com.fsck.k9.activity.AlternateRecipientAdapter
import com.fsck.k9.activity.AlternateRecipientAdapter.AlternateRecipientListener
import com.fsck.k9.activity.compose.RecipientAdapter
import com.fsck.k9.activity.compose.RecipientLoader
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.logging.Timber
import com.fsck.k9.mail.Address
import com.fsck.k9.ui.R
import com.fsck.k9.ui.compose.OnSetImageDrawableListener
import com.fsck.k9.ui.compose.RecipientCircleImageView
import com.google.android.material.textview.MaterialTextView
import com.tokenautocomplete.TokenCompleteTextView
import de.hdodenhof.circleimageview.CircleImageView
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

class RecipientSelectView : TokenCompleteTextView<RecipientSelectView.Recipient>,
    LoaderManager.LoaderCallbacks<List<RecipientSelectView.Recipient>>,
    AlternateRecipientListener {
    private val emailAddressParser = get(
        UserInputEmailAddressParser::class.java
    )

    private var adapter: RecipientAdapter? = null
    private var cryptoProvider: String? = null
    private var showCryptoEnabled = false
    private var loaderManager: LoaderManager? = null

    private var alternatesPopup: ListPopupWindow? = null
    private var alternatesAdapter: AlternateRecipientAdapter? = null
    private var alternatesPopupRecipient: Recipient? = null
    private var listener: TokenListener<Recipient?>? = null
    private var tokenTextSize = FontSizes.FONT_DEFAULT

    constructor(context: Context) : super(context) {
        initView(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initView(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        initView(context)
    }

    private fun initView(context: Context) {
        // TODO: validator?

        alternatesPopup = ListPopupWindow(context)
        alternatesAdapter = AlternateRecipientAdapter(context, this)
        alternatesPopup!!.setAdapter(alternatesAdapter)

        // if a token is completed, pick an entry based on best guess.
        // Note that we override performCompletion, so this doesn't actually do anything
        performBestGuess(true)

        adapter = RecipientAdapter(context)
        setAdapter(adapter)

        isLongClickable = true
    }

    override fun shouldIgnoreToken(token: Recipient): Boolean {
        // don't allow duplicates, based on equality of recipient objects, which is email addresses
        return objects.contains(token)
    }

    fun setTokenTextSize(tokenTextSize: Int) {
        this.tokenTextSize = tokenTextSize
    }

    override fun getViewForObject(recipient: Recipient): View {
        val view = inflateLayout()

        val holder = RecipientTokenViewHolder(view)
        view.tag = holder

        bindObjectView(recipient, view)

        return view
    }

    @SuppressLint("InflateParams")
    private fun inflateLayout(): View {
        val layoutInflater = LayoutInflater.from(context)
        val view = layoutInflater.inflate(R.layout.recipient_token_item, null, false)

        // Since the recipient chip views are not part of the view hierarchy we need to manually invalidate this
        // RecipientSelectView whenever a contact picture was loaded in order for the image to be drawn.
        val contactPhotoView = view.findViewById<RecipientCircleImageView>(R.id.contact_photo)
        contactPhotoView.onSetImageDrawableListener = OnSetImageDrawableListener { this.redrawTokens() }

        return view
    }

    private fun bindObjectView(recipient: Recipient, view: View) {
        val holder = view.tag as RecipientTokenViewHolder

        holder.vName.text = recipient.displayNameOrAddress
        if (tokenTextSize != FontSizes.FONT_DEFAULT) {
            holder.vName.setTextSize(TypedValue.COMPLEX_UNIT_SP, tokenTextSize.toFloat())
        }

        RecipientAdapter.setContactPhotoOrPlaceholder(context, holder.vContactPhoto, recipient)

        val hasCryptoProvider = cryptoProvider != null
        if (!hasCryptoProvider) {
            holder.hideCryptoState()
            return
        }

        val isAvailable = recipient.cryptoStatus == RecipientCryptoStatus.AVAILABLE_TRUSTED ||
            recipient.cryptoStatus == RecipientCryptoStatus.AVAILABLE_UNTRUSTED

        holder.showCryptoState(isAvailable, showCryptoEnabled)
    }

    private fun parseRecipients(text: String): List<Recipient> {
        try {
            val parsedAddresses = emailAddressParser.parse(text)

            if (parsedAddresses.isEmpty()) {
                error = context.getString(R.string.recipient_error_parse_failed)
                return listOf()
            }

            val recipients: MutableList<Recipient> = ArrayList()
            for (a in parsedAddresses) {
                recipients.add(Recipient(a))
            }
            return recipients
        } catch (e: NonAsciiEmailAddressException) {
            error = context.getString(R.string.recipient_error_non_ascii)
            return listOf()
        }
    }

    override fun defaultObject(completionText: String): Recipient? {
        val recipients = parseRecipients(completionText)
        if (!recipients.isEmpty()) {
            return recipients[0]
        }
        return null
    }

    fun setLoaderManager(loaderManager: LoaderManager?) {
        this.loaderManager = loaderManager
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (loaderManager != null) {
            loaderManager!!.destroyLoader(LOADER_ID_ALTERNATES)
            loaderManager!!.destroyLoader(LOADER_ID_FILTERING)
            loaderManager = null
        }
    }

    override fun onFocusChanged(hasFocus: Boolean, direction: Int, previous: Rect?) {
        if (!hasFocus) {
            performCompletion()
        }

        super.onFocusChanged(hasFocus, direction, previous)
        if (hasFocus) {
            displayKeyboard()
        }
    }

    /**
     * TokenCompleteTextView removes composing strings, and etc, but leaves internal composition
     * predictions partially constructed. Changing either/or the Selection or Candidate start/end
     * positions, forces the IMM to reset cleaner.
     */
    override fun replaceText(text: CharSequence) {
        super.replaceText(text)

        val imm = context.getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager
        imm.updateSelection(this, selectionStart, selectionEnd, -1, -1)
    }

    private fun displayKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            ?: return
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun showDropDown() {
        val cursorIsValid = adapter != null
        if (!cursorIsValid) {
            return
        }

        super.showDropDown()
    }

    override fun performCompletion() {
        if (listSelection == ListView.INVALID_POSITION && enoughToFilter()) {
            val recipients = parseRecipients(currentCompletionText())
            if (!recipients.isEmpty()) {
                clearCompletionText()
                for (r in recipients) {
                    addObjectSync(r)
                }
            }
        } else {
            super.performCompletion()
        }
    }

    override fun performFiltering(text: CharSequence, keyCode: Int) {
        if (loaderManager == null) {
            return
        }

        val query = currentCompletionText()
        if (TextUtils.isEmpty(query) || query.length < MINIMUM_LENGTH_FOR_FILTERING) {
            loaderManager!!.destroyLoader(LOADER_ID_FILTERING)
            return
        }

        val args = Bundle()
        args.putString(ARG_QUERY, query)
        loaderManager!!.restartLoader<List<Recipient>>(
            LOADER_ID_FILTERING, args,
            this
        )
    }

    fun setCryptoProvider(cryptoProvider: String?) {
        this.cryptoProvider = cryptoProvider
    }

    fun setShowCryptoEnabled(showCryptoEnabled: Boolean) {
        this.showCryptoEnabled = showCryptoEnabled

        redrawAllTokens()
    }

    private fun redrawAllTokens() {
        val text = text ?: return

        val recipientSpans = text.getSpans(
            0, text.length,
            RecipientTokenSpan::class.java
        )
        for (recipientSpan in recipientSpans) {
            bindObjectView(recipientSpan.token!!, recipientSpan.view)
        }

        invalidate()
        redrawTokens()
        invalidateCursorPositionHack()
    }

    fun addRecipients(vararg recipients: Recipient?) {
        for (recipient in recipients) {
            addObjectSync(recipient)
        }
    }

    val addresses: Array<Address?>
        get() {
            val recipients = objects
            val address =
                arrayOfNulls<Address>(recipients.size)
            for (i in address.indices) {
                address[i] = recipients[i]!!.address
            }

            return address
        }

    private fun showAlternates(recipient: Recipient?) {
        if (loaderManager == null) {
            return
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)

        alternatesPopupRecipient = recipient
        loaderManager!!.restartLoader<List<Recipient>>(
            LOADER_ID_ALTERNATES, null,
            this@RecipientSelectView
        )
    }

    fun postShowAlternatesPopup(data: List<Recipient?>) {
        // We delay this call so the soft keyboard is gone by the time the popup is layouted
        Handler().post { showAlternatesPopup(data) }
    }

    fun showAlternatesPopup(data: List<Recipient?>) {
        if (loaderManager == null) {
            return
        }

        // Copy anchor settings from the autocomplete dropdown
        val anchorView = rootView.findViewById<View>(dropDownAnchor)
        alternatesPopup!!.anchorView = anchorView
        alternatesPopup!!.width = dropDownWidth

        alternatesAdapter!!.setCurrentRecipient(alternatesPopupRecipient)
        alternatesAdapter!!.setAlternateRecipientInfo(data)

        // Clear the checked item.
        alternatesPopup!!.show()
        val listView = alternatesPopup!!.listView
        listView!!.choiceMode = ListView.CHOICE_MODE_SINGLE
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && alternatesPopup!!.isShowing) {
            alternatesPopup!!.dismiss()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        alternatesPopup!!.dismiss()
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<List<Recipient>> {
        when (id) {
            LOADER_ID_FILTERING -> {
                val query = if (args != null && args.containsKey(ARG_QUERY)) args.getString(ARG_QUERY) else ""
                adapter!!.setHighlight(query)
                return RecipientLoader(context, cryptoProvider, query)
            }

            LOADER_ID_ALTERNATES -> {
                val contactLookupUri = alternatesPopupRecipient!!.contactLookupUri
                return if (contactLookupUri != null) {
                    RecipientLoader(context, cryptoProvider, contactLookupUri, true)
                } else {
                    RecipientLoader(context, cryptoProvider, alternatesPopupRecipient!!.address)
                }
            }
        }

        throw IllegalStateException("Unknown Loader ID: $id")
    }

    override fun onLoadFinished(loader: Loader<List<Recipient>>, data: List<Recipient>) {
        if (loaderManager == null) {
            return
        }

        when (loader.id) {
            LOADER_ID_FILTERING -> {
                adapter!!.setRecipients(data)
            }

            LOADER_ID_ALTERNATES -> {
                postShowAlternatesPopup(data)
                loaderManager!!.destroyLoader(LOADER_ID_ALTERNATES)
            }
        }
    }

    override fun onLoaderReset(loader: Loader<List<Recipient>>) {
        if (loader.id == LOADER_ID_FILTERING) {
            adapter!!.setHighlight(null)
            adapter!!.setRecipients(null)
        }
    }

    fun tryPerformCompletion(): Boolean {
        if (!hasUncompletedText()) {
            return false
        }
        val previousNumRecipients = tokenCount
        performCompletion()
        val numRecipients = tokenCount

        return previousNumRecipients != numRecipients
    }

    private val tokenCount: Int
        get() = objects.size

    fun hasUncompletedText(): Boolean {
        val currentCompletionText = currentCompletionText()
        return !TextUtils.isEmpty(currentCompletionText) && !isPlaceholderText(currentCompletionText)
    }

    override fun onRecipientRemove(currentRecipient: Recipient) {
        alternatesPopup!!.dismiss()
        removeObjectSync(currentRecipient)
    }

    override fun onRecipientChange(recipientToReplace: Recipient, alternateAddress: Recipient) {
        alternatesPopup!!.dismiss()

        val currentRecipients = objects
        val indexOfRecipient = currentRecipients.indexOf(recipientToReplace)
        if (indexOfRecipient == -1) {
            Timber.e("Tried to refresh invalid view token!")
            return
        }
        val currentRecipient = currentRecipients[indexOfRecipient]

        currentRecipient!!.address = alternateAddress.address
        currentRecipient.addressLabel = alternateAddress.addressLabel
        currentRecipient.cryptoStatus = alternateAddress.cryptoStatus

        val recipientTokenView = getTokenViewForRecipient(currentRecipient)
        if (recipientTokenView == null) {
            Timber.e("Tried to refresh invalid view token!")
            return
        }

        bindObjectView(currentRecipient, recipientTokenView)

        if (listener != null) {
            listener!!.onTokenChanged(currentRecipient)
        }

        invalidate()
        redrawTokens()
        invalidateCursorPositionHack()
    }

    override fun onRecipientAddressCopy(currentRecipient: Recipient) {
        val clipboardManager = get(
            ClipboardManager::class.java
        )
        val label = context.resources.getString(R.string.clipboard_label_name_and_email_address)
        val nameAndEmailAddress = currentRecipient.address.toString()
        clipboardManager.setText(label, nameAndEmailAddress)
    }

    /**
     * Changing the size of our RecipientTokenSpan doesn't seem to redraw the cursor in the new position. This will
     * make sure the cursor position is recalculated.
     */
    private fun invalidateCursorPositionHack() {
        val oldStart = selectionStart
        val oldEnd = selectionEnd

        // The selection values need to actually change in order for the cursor to be redrawn. If the cursor already
        // is at position 0 this won't trigger a redraw. But that's fine because the size of our span can't influence
        // cursor position 0.
        setSelection(0)

        setSelection(oldStart, oldEnd)
    }

    /**
     * This method builds the span given a recipient object. We override it with identical
     * functionality, but using the custom RecipientTokenSpan class which allows us to
     * retrieve the view for redrawing at a later point.
     */
    override fun buildSpanForObject(obj: Recipient?): TokenImageSpan? {
        if (obj == null) {
            return null
        }

        val tokenView = getViewForObject(obj)
        return RecipientTokenSpan(tokenView, obj)
    }

    /**
     * Find the token view tied to a given recipient. This method relies on spans to
     * be of the RecipientTokenSpan class, as created by the buildSpanForObject method.
     */
    private fun getTokenViewForRecipient(currentRecipient: Recipient?): View? {
        val text = text ?: return null

        val recipientSpans = text.getSpans(
            0, text.length,
            RecipientTokenSpan::class.java
        )
        for (recipientSpan in recipientSpans) {
            if (recipientSpan.token == currentRecipient) {
                return recipientSpan.view
            }
        }

        return null
    }

    /**
     * We use a specialized version of TokenCompleteTextView.TokenListener as well,
     * adding a callback for onTokenChanged.
     */
    fun setTokenListener(listener: TokenListener<Recipient?>?) {
        super.setTokenListener(listener)
        this.listener = listener
    }

    enum class RecipientCryptoStatus {
        UNDEFINED,
        UNAVAILABLE,
        AVAILABLE_UNTRUSTED,
        AVAILABLE_TRUSTED
    }

    interface TokenListener<T> : TokenCompleteTextView.TokenListener<T> {
        fun onTokenChanged(token: T)
    }

    private inner class RecipientTokenSpan(val view: View, recipient: Recipient?) :
        TokenImageSpan(view, recipient) {

        override fun onClick() {
            showAlternates(token)
        }

        override fun draw(
            canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int,
            bottom: Int, paint: Paint
        ) {
            super.draw(canvas, text, start, end, x, top, y, bottom, paint)

            // Dispatch onPreDraw event so image loading using Glide will work properly.
            view.findViewById<View>(R.id.contact_photo).viewTreeObserver.dispatchOnPreDraw()
        }
    }

    private class RecipientTokenViewHolder(view: View) {
        val vName: MaterialTextView = view.findViewById(android.R.id.text1)
        val vContactPhoto: CircleImageView =
            view.findViewById(R.id.contact_photo)
        val cryptoStatus: View = view.findViewById(R.id.contact_crypto_status_icon)
        val cryptoStatusEnabled: View = view.findViewById(R.id.contact_crypto_status_icon_enabled)
        val cryptoStatusError: View = view.findViewById(R.id.contact_crypto_status_icon_error)

        fun showCryptoState(isAvailable: Boolean, isShowEnabled: Boolean) {
            cryptoStatus.visibility =
                if (!isShowEnabled && isAvailable) VISIBLE else GONE
            cryptoStatusEnabled.visibility =
                if (isShowEnabled && isAvailable) VISIBLE else GONE
            cryptoStatusError.visibility =
                if (isShowEnabled && !isAvailable) VISIBLE else GONE
        }

        fun hideCryptoState() {
            cryptoStatus.visibility = GONE
            cryptoStatusEnabled.visibility = GONE
            cryptoStatusError.visibility = GONE
        }
    }

    class Recipient : Serializable {
        // null means the address is not associated with a contact
        val contactId: Long?
        val contactLookupKey: String?

        @JvmField
        var address: Address

        @JvmField
        var addressLabel: String? = null
        @JvmField
        val timesContacted: Int
        @JvmField
        val sortKey: String?
        @JvmField
        val starred: Boolean

        @JvmField
        @Transient
        // null if the contact has no photo. transient because we serialize this manually, see below.
        var photoThumbnailUri: Uri? = null

        var cryptoStatus: RecipientCryptoStatus

        constructor(address: Address) {
            this.address = address
            this.contactId = null
            this.cryptoStatus = RecipientCryptoStatus.UNDEFINED
            this.contactLookupKey = null
            timesContacted = 0
            sortKey = null
            starred = false
        }

        constructor(
            name: String?, email: String?, addressLabel: String?, contactId: Long, lookupKey: String?,
            timesContacted: Int, sortKey: String?, starred: Boolean
        ) {
            this.address = Address(email, name)
            this.contactId = contactId
            this.addressLabel = addressLabel
            this.cryptoStatus = RecipientCryptoStatus.UNDEFINED
            this.contactLookupKey = lookupKey
            this.timesContacted = timesContacted
            this.sortKey = sortKey
            this.starred = starred
        }

        val displayNameOrAddress: String
            get() {
                val displayName =
                    if (isShowCorrespondentNames) displayName else null

                if (displayName != null) {
                    return displayName
                }

                return address.address
            }

        val isValidEmailAddress: Boolean
            get() = (address.address != null)

        fun getDisplayNameOrUnknown(context: Context): String {
            val displayName = displayName
            if (displayName != null) {
                return displayName
            }

            return context.getString(R.string.unknown_recipient)
        }

        fun getNameOrUnknown(context: Context): String {
            val name = address.personal
            if (name != null) {
                return name
            }

            return context.getString(R.string.unknown_recipient)
        }

        private val displayName: String?
            get() {
                if (TextUtils.isEmpty(address.personal)) {
                    return null
                }

                return address.personal
            }

        fun getCryptoStatus(): RecipientCryptoStatus {
            return cryptoStatus
        }

        fun setCryptoStatus(cryptoStatus: RecipientCryptoStatus) {
            this.cryptoStatus = cryptoStatus
        }

        val contactLookupUri: Uri?
            get() {
                if (contactId == null) {
                    return null
                }

                return ContactsContract.Contacts.getLookupUri(contactId, contactLookupKey)
            }

        override fun equals(o: Any?): Boolean {
            // Equality is entirely up to the address
            return o is Recipient && address == o.address
        }

        override fun toString(): String {
            return address.toString()
        }

        @Throws(IOException::class)
        private fun writeObject(oos: ObjectOutputStream) {
            oos.defaultWriteObject()

            // custom serialization, Android's Uri class is not serializable
            if (photoThumbnailUri != null) {
                oos.writeInt(1)
                oos.writeUTF(photoThumbnailUri.toString())
            } else {
                oos.writeInt(0)
            }
        }

        @Throws(ClassNotFoundException::class, IOException::class)
        private fun readObject(ois: ObjectInputStream) {
            ois.defaultReadObject()

            // custom deserialization, Android's Uri class is not serializable
            if (ois.readInt() != 0) {
                val uriString = ois.readUTF()
                photoThumbnailUri = Uri.parse(uriString)
            }
        }
    }

    companion object {
        private const val MINIMUM_LENGTH_FOR_FILTERING = 2

        private const val ARG_QUERY = "query"

        private const val LOADER_ID_FILTERING = 0
        private const val LOADER_ID_ALTERNATES = 1

        private fun isPlaceholderText(currentCompletionText: String): Boolean {
            // TODO string matching here is sort of a hack, but it's somewhat reliable and the info isn't easily available
            return currentCompletionText.startsWith("+") && currentCompletionText.substring(1)
                .matches("[0-9]+".toRegex())
        }
    }
}
