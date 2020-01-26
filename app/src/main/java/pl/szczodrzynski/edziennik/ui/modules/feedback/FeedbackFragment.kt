package pl.szczodrzynski.edziennik.ui.modules.feedback

import android.content.BroadcastReceiver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import coil.Coil
import coil.api.load
import com.github.bassaer.chatmessageview.model.IChatUser
import com.github.bassaer.chatmessageview.model.Message
import com.github.bassaer.chatmessageview.view.ChatView
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.data.api.events.FeedbackMessageEvent
import pl.szczodrzynski.edziennik.data.api.szkolny.SzkolnyApi
import pl.szczodrzynski.edziennik.data.db.entity.FeedbackMessage
import pl.szczodrzynski.edziennik.databinding.FragmentFeedbackBinding
import pl.szczodrzynski.edziennik.onClick
import pl.szczodrzynski.edziennik.utils.Themes
import pl.szczodrzynski.edziennik.utils.Utils
import pl.szczodrzynski.edziennik.utils.Utils.openUrl
import java.util.*
import kotlin.coroutines.CoroutineContext

class FeedbackFragment : Fragment(), CoroutineScope {

    private lateinit var app: App
    private lateinit var activity: MainActivity
    private lateinit var b: FragmentFeedbackBinding

    private val job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    private val chatView: ChatView by lazy { b.chatView }
    private val api by lazy { SzkolnyApi(app) }
    private var isDev = false
    private var currentDeviceId: String? = null

    private var receiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        activity = (getActivity() as MainActivity?) ?: return null
        if (context == null)
            return null
        app = activity.application as App
        context!!.theme.applyStyle(Themes.appTheme, true)
        // activity, context and profile is valid
        b = FragmentFeedbackBinding.inflate(inflater)
        return b.root
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onFeedbackMessageEvent(event: FeedbackMessageEvent) {
        EventBus.getDefault().removeStickyEvent(event)
        val message = event.message
        val chatMessage = getChatMessage(message)
        if (message.received) chatView.receive(chatMessage)
        else chatView.send(chatMessage)
    }

    private val users = mutableMapOf(
            0 to User(0, "Ja", null)
    )
    private fun getUser(message: FeedbackMessage): User {
        val userId = message.devId ?: if (message.received) 1 else 0
        return users[userId] ?: run {
            User(userId, message.senderName, message.devImage).also { users[userId] = it }
        }
    }

    private fun getChatMessage(message: FeedbackMessage): Message = Message.Builder()
            .setUser(getUser(message))
            .setRight(!message.received)
            .setText(message.text)
            .setSendTime(Calendar.getInstance().apply { timeInMillis = message.sentTime })
            .hideIcon(!message.received)
            .build()

    private fun launchDeviceSelection() {
        if (!isDev)
            return
        launch {
            val messages = withContext(Dispatchers.Default) { app.db.feedbackMessageDao().allWithCountNow }
            val popupMenu = PopupMenu(activity, b.targetDeviceDropDown)
            messages.forEachIndexed { index, m ->
                popupMenu.menu.add(0, index, index, "${m.senderName} ${m.deviceName} (${m.count})")
            }
            popupMenu.setOnMenuItemClickListener { item ->
                b.targetDeviceDropDown.setText(item.title)
                chatView.getMessageView().removeAll()
                val message = messages[item.itemId]
                currentDeviceId = message.deviceId
                this@FeedbackFragment.launch { loadMessages() }
                false
            }
            popupMenu.show()
        }
    }

    private suspend fun loadMessages(messageList: List<FeedbackMessage>? = null) {
        /*if (messageList != null && messageList.isNotEmpty())
            return*/
        val messages = messageList ?: withContext(Dispatchers.Default) {
            if (currentDeviceId == null)
                app.db.feedbackMessageDao().allNow
            else
                app.db.feedbackMessageDao().getByDeviceIdNow(currentDeviceId!!)
        }

        if (messages.isNotEmpty()) {
            b.chatLayout.visibility = View.VISIBLE
            b.inputLayout.visibility = View.GONE
        }

        messages.forEach {
            val chatMessage = getChatMessage(it)
            if (it.received) chatView.receive(chatMessage)
            else chatView.send(chatMessage)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // TODO check if app, activity, b can be null
        if (!isAdded)
            return

        b.faqText.setOnClickListener { openFaq() }
        b.faqButton.setOnClickListener { openFaq() }

        with(chatView) {
            setLeftBubbleColor(Utils.getAttr(activity, R.attr.colorSurface))
            setLeftMessageTextColor(Utils.getAttr(activity, android.R.attr.textColorPrimary))
            setRightBubbleColor(Utils.getAttr(activity, R.attr.colorPrimary))
            setRightMessageTextColor(Color.WHITE)
            setSendButtonColor(Utils.getAttr(activity, R.attr.colorAccent))
            setSendIcon(R.drawable.ic_action_send)
            setInputTextHint("Napisz...")
            setMessageMarginTop(5)
            setMessageMarginBottom(5)
        }

        launch {
            val messages = withContext(Dispatchers.Default) {
                val messages = app.db.feedbackMessageDao().allNow
                isDev = App.devMode && messages.any { it.deviceId != null }
                messages
            }

            b.targetDeviceLayout.visibility = if (isDev) View.VISIBLE else View.GONE
            b.targetDeviceDropDown.onClick {
                launchDeviceSelection()
            }

            if (isDev) {
                messages.firstOrNull()?.let {
                    currentDeviceId = it.deviceId
                    b.targetDeviceDropDown.setText("${it.senderName} ${it.deviceName}")
                }
                b.chatLayout.visibility = View.VISIBLE
                b.inputLayout.visibility = View.GONE
            }
            else if (messages.isEmpty()) {
                b.chatLayout.visibility = View.GONE
                b.inputLayout.visibility = View.VISIBLE
            }

            loadMessages(messages)

            b.sendButton.onClick {
                send(b.textInput.text.toString())
            }
            chatView.setOnClickSendButtonListener(View.OnClickListener {
                send(chatView.inputText)
            })
        }
    }

    inner class User(val id: Int, val userName: String, val image: String?) : IChatUser {
        override fun getIcon(): Bitmap? {
            if (image == null)
                return BitmapFactory.decodeResource(activity.resources, R.drawable.profile_)
            return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { bmp ->
                launch {
                    Coil.load(activity, image) {
                        target {
                            val canvas = Canvas(bmp)
                            it.setBounds(0, 0, bmp.width, bmp.height)
                            it.draw(canvas)
                        }
                    }
                }
            }
        }

        override fun getId() = id.toString()
        override fun getName() = userName
        override fun setIcon(bmp: Bitmap) {}
    }

    private fun send(text: String?) {
        if (text?.isEmpty() != false) {
            Toast.makeText(activity, "Podaj treść wiadomości.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isDev && currentDeviceId == null || currentDeviceId == "szkolny.eu") {
            Toast.makeText(activity, "Wybierz urządzenie docelowe.", Toast.LENGTH_SHORT).show()
            return
        }

        launch {
            val message = withContext(Dispatchers.Default) {
                try {
                    api.sendFeedbackMessage(
                            senderName = App.profile.accountName ?: App.profile.studentNameLong,
                            targetDeviceId = if (isDev) currentDeviceId else null,
                            text = text
                    )?.also {
                        app.db.feedbackMessageDao().add(it)
                    }
                } catch (ignore: Exception) { null }
            }

            if (message == null) {
                Toast.makeText(app, "Nie udało się wysłać wiadomości.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            b.chatLayout.visibility = View.VISIBLE
            b.inputLayout.visibility = View.GONE

            b.textInput.text = null
            b.chatView.inputText = ""

            val chatMessage = getChatMessage(message)
            if (message.received) chatView.receive(chatMessage)
            else chatView.send(chatMessage)
        }
    }

    private fun openFaq() {
        openUrl(activity, "http://szkolny.eu/pomoc/")
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
    }

    override fun onPause() {
        super.onPause()
        EventBus.getDefault().unregister(this)
    }
}
